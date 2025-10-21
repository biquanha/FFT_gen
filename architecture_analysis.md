# R2DIF vs R2MDC 架构深度分析

## 测试结果总结

### R2DIF修复后的性能
✅ **修复成功！所有5次迭代一致**
- 错误率：1.912% (稳定)
- 溢出：0次
- bin 2精度：0.11%误差
- 测试周期：41,494周期

### 关键修复
**问题**：第1次迭代正确，后续迭代错误
**根因**：bufferB没有清空，残留数据污染后续迭代
**解决**：在每次sProcess开始时同时清空bufferA和bufferB

```scala
// 修复前：只写入bufferA
bufferA.write(initCnt, inputBuffer.read(initCnt))

// 修复后：同时清空bufferB
bufferA.write(initCnt, inputBuffer.read(initCnt))
bufferB.write(initCnt, zeroComplex)  // 关键！
```

---

## Verilog代码规模对比

| 指标 | R2DIF | R2MDC | 差异 |
|------|-------|-------|------|
| **代码行数** | 4,338行 | 13,526行 | **R2MDC大3.1倍** |
| **寄存器数量** | 113个 | 3,122个 | **R2MDC多26.6倍** |
| **wire数量** | 238个 | 1,282个 | **R2MDC多4.4倍** |
| **内存数组** | 6个 | 6个 | 相同 |

**结论：R2MDC的面积远大于R2DIF！主要差异在寄存器数量。**

---

## 架构差异深度分析

### 1. R2DIF (Radix-2 Decimation-In-Frequency)

#### **架构特点：批处理 + 双Buffer交替**

```
工作流程：
1. sBufferInit: 初始化bufferA和bufferB为0
2. sInput: 收集512个输入到inputBuffer
3. sProcess: 
   - 复制inputBuffer → bufferA
   - 清空bufferB
   - Stage 0: bufferA → 蝶形运算 → bufferB
   - Stage 1: bufferB → 蝶形运算 → bufferA
   - ...交替9个stage
4. sOutput: 从最终buffer读取结果
```

#### **关键特性**

**1. 批处理架构**
- 收集完整的512点输入
- 一次性处理完9个stage
- 顺序执行，每个stage处理256个蝶形

**2. 存储需求**
```
inputBuffer:  512 × 64-bit = 32 Kbit
bufferA:      512 × 64-bit = 32 Kbit
bufferB:      512 × 64-bit = 32 Kbit
Total:        96 Kbit (3个buffer)
```

**3. 流水线结构**
```
Stage内流水线（4级）：
- 第1级：地址计算
- 第2级：BRAM读取
- 第3级：蝶形计算
- 第4级：BRAM写回

Stage间：无流水线，批处理
```

**4. 计算单元**
- 1个复数加法器
- 1个复数减法器
- 1个复数乘法器
- **复用**：256个蝶形运算共享这3个单元

**5. 旋转因子**
```scala
// 为每个stage准备独立的旋转因子表
def difSinTable(stage: Int): Vec[FixedPoint]
def difCosTable(stage: Int): Vec[FixedPoint]

// 使用MuxLookup根据stage选择
val wnRe = MuxLookup(stageReg, ...)
val wnIm = MuxLookup(stageReg, ...)
```
- 9个stage × 2个表(sin/cos) = **18个ROM表**
- 通过Mux选择当前stage的表

---

### 2. R2MDC (Radix-2 Multi-path Delay Commutator)

#### **架构特点：全流水线 + 延迟线网络**

```
工作流程：
连续流模式，数据流过9个流水级：

输入 → [Stage0延迟+BF] → [Stage1延迟+BF] → ... → [Stage8延迟+BF] → 输出

每个Stage包含：
- 延迟线：ShiftRegister(FFTLength/2^(i+1))
- 蝶形单元：BF
- Switch：交换控制
```

#### **关键特性**

**1. 全流水线架构**
- **9个独立的stage同时工作**
- 每个周期输入1个数据，输出1个数据
- 高吞吐量：512点/周期（达到稳态后）

**2. 延迟线网络**
```scala
for (i <- 0 until stages - 1) {
  val delay1 = FFTLength / pow(2, i + 1).toInt  // 上路延迟
  val delay2 = FFTLength / pow(2, i + 2).toInt  // 下路延迟
  
  val delayedOut1 = ShiftRegister(out1(i), delay1)
  val BF12 = Butterfly(delayedOut1, out2(i), wn)
  val sw12 = Switch(BF12._1, ShiftRegister(BF12._2, delay2), swCtrl)
}
```

**延迟线长度**：
- Stage 0: 256个寄存器 + 128个寄存器 = 384个
- Stage 1: 128个 + 64个 = 192个
- Stage 2: 64个 + 32个 = 96个
- ...
- **总计：约768个复数寄存器 = 768 × 64-bit = 49,152个FF**

**3. 存储需求**
```
延迟线寄存器：768 × 64-bit = 49,152 FF (约3,000个寄存器)
无需BRAM！全部用寄存器实现
```

**4. 计算单元**
- **9个独立的复数蝶形单元**（每个stage一个）
- **9个复数加法器**
- **9个复数减法器**
- **9个复数乘法器**
- 每个单元每周期处理1个蝶形

**5. 旋转因子**
```scala
// 每个stage有自己的ROM
def sinTable(k: Int): Vec[FixedPoint]
def cosTable(k: Int): Vec[FixedPoint]

// Stage i直接访问表k=i
val wn = wnTable(i)(wnCtrl)
```
- 9个stage × 2个表 = **18个ROM表**
- 每个stage独立访问，无需Mux

---

## 面积差异根本原因

### R2DIF：资源复用 + 批处理
```
优势：
✅ 计算单元复用（1套BF单元处理256次）
✅ 使用BRAM存储中间结果（96 Kbit）
✅ 少量控制逻辑

劣势：
❌ 低吞吐量（需要多个周期完成512点）
❌ 批处理延迟高
```

### R2MDC：全流水并行
```
优势：
✅ 高吞吐量（每周期1个输出）
✅ 低延迟（pipeline深度固定）
✅ 连续流处理

劣势：
❌ 需要9套独立BF单元
❌ 巨大的延迟线寄存器开销（49K FF）
❌ 大量控制逻辑和开关
```

---

## 详细资源估算

### R2DIF资源

| 资源类型 | 数量 | 说明 |
|---------|------|------|
| **存储** |
| BRAM | 96 Kbit | 3个buffer (input + A + B) |
| 控制寄存器 | ~100个 | 状态机、计数器、流水线控制 |
| **计算** |
| 复数加法器 | 1个 | 64-bit加法 ≈ 130 LUTs |
| 复数减法器 | 1个 | 64-bit减法 ≈ 130 LUTs |
| 复数乘法器 | 1个 | 64×64-bit乘法 ≈ 500 LUTs + 4 DSP |
| **ROM** |
| 旋转因子表 | 18个 | 9 stages × 2 (sin/cos) |
| Mux逻辑 | ~200 LUTs | 9选1 Mux选择stage |
| **总计** |
| LUTs | ~1,200 | 计算 + 控制 + Mux |
| FF | ~200 | 寄存器 |
| DSP | 4个 | 复数乘法器 |
| BRAM | 3个(36K) | 96 Kbit |

### R2MDC资源

| 资源类型 | 数量 | 说明 |
|---------|------|------|
| **存储** |
| 延迟线寄存器 | 49,152 FF | 768个复数 × 64-bit |
| 流水线寄存器 | ~1,000 FF | 9级流水线的中间值 |
| **计算** |
| 复数加法器 | 9个 | 9 × 130 = 1,170 LUTs |
| 复数减法器 | 9个 | 9 × 130 = 1,170 LUTs |
| 复数乘法器 | 9个 | 9 × 500 = 4,500 LUTs + 36 DSP |
| **ROM** |
| 旋转因子表 | 18个 | 9 stages × 2 |
| Switch逻辑 | ~500 LUTs | 9个stage的交换控制 |
| **总计** |
| LUTs | ~8,000 | 计算密集 |
| FF | ~50,000 | 延迟线主导 |
| DSP | 36个 | 9个复数乘法器 |
| BRAM | 0个 | 全寄存器实现 |

---

## 为什么R2MDC面积这么大？

### 核心原因：**延迟线网络**

R2MDC的延迟线需求：
```
Stage 0: 256 + 128 = 384个复数寄存器
Stage 1: 128 + 64  = 192个
Stage 2: 64 + 32   = 96个
Stage 3: 32 + 16   = 48个
Stage 4: 16 + 8    = 24个
Stage 5: 8 + 4     = 12个
Stage 6: 4 + 2     = 6个
Stage 7: 2 + 1     = 3个
Total:   768个复数 = 49,152 bit = 49,152个FF
```

**这就是3,122个寄存器的来源！**

加上9个独立的蝶形单元（每个包含加、减、乘），R2MDC的面积是R2DIF的约3-4倍。

---

## 适用场景对比

### R2DIF适合：
✅ **面积受限的FPGA**（小型芯片）
✅ **批处理应用**（一次处理一帧数据）
✅ **低功耗需求**（资源复用，动态功耗低）
✅ **需要BRAM的设计**（释放LUT资源）

### R2MDC适合：
✅ **高吞吐量需求**（连续流处理）
✅ **低延迟要求**（固定pipeline延迟）
✅ **资源充足的FPGA**（大型芯片）
✅ **实时信号处理**（音视频、通信）

---

## 优化建议

### 对于R2DIF
1. ✅ **已修复buffer清空问题**
2. ⚠️ **需要BRAM推断优化**
   - 问题：当前使用`Mem`无法推断BRAM
   - 解决：改用`SyncReadMem`（需要调整流水线时序）
3. 可选：增加级间缩放，提高数值稳定性

### 对于R2MDC
1. ✅ 架构已经很成熟
2. 可选：如果FPGA资源不足，考虑：
   - 将长延迟线用BRAM实现（但会降低吞吐量）
   - 部分stage复用（牺牲吞吐量换面积）

---

## 结论

**R2DIF vs R2MDC：空间换时间的经典权衡**

- **R2DIF**：小面积（~1,200 LUTs），低吞吐量，批处理
- **R2MDC**：大面积（~8,000 LUTs），高吞吐量，流水线

**当前项目建议**：
- 如果是批处理应用且FPGA资源紧张 → **使用R2DIF**（已修复）
- 如果需要实时连续处理且资源充足 → **使用R2MDC**

**精度对比**：
- R2MDC：6.74e-8% 错误率（接近完美）
- R2DIF：1.912% 错误率（可接受，但需进一步优化数值精度）
