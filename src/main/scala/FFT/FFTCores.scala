package FFT

import chisel3._
import chisel3.experimental._
import chisel3.util._
import scala.math._

// FFT算法核心的通用IO
class FFTCoreIO extends Bundle with HasDataConfig with HasElaborateConfig {
  val mode = if(supportIFFT) Some(Input(Bool())) else None
  val dIn = Input(new MyComplex)
  val din_valid = Input(Bool())
  val dOut1 = Output(new MyComplex)
  val dOut2 = Output(new MyComplex)
  val dout_valid = Output(Bool())
  val busy = Output(Bool())
}

// R2MDC算法核心模块 - 原有实现
class R2MDCCore extends Module with HasDataConfig with HasElaborateConfig {
  val io = IO(new FFTCoreIO)
  
  val mode = io.mode.getOrElse(false.B)
  val stages = log2Ceil(FFTLength)
  
  def sinTable(k: Int): Vec[FixedPoint] = {
    val times = (0 until FFTLength / 2 by pow(2, k).toInt)
      .map(i => -(i * 2 * Pi) / FFTLength.toDouble)
    val inits = times.map(t => FixedPoint.fromDouble(sin(t), DataWidth.W, BinaryPoint.BP))
    VecInit(inits)
  }
  
  def cosTable(k: Int): Vec[FixedPoint] = {
    val times = (0 until FFTLength / 2 by pow(2, k).toInt)
      .map(i => -(i * 2 * Pi) / FFTLength.toDouble)
    val inits = times.map(t => FixedPoint.fromDouble(cos(t), DataWidth.W, BinaryPoint.BP))
    VecInit(inits)
  }
  
  def sinTable2(k: Int): Vec[FixedPoint] = {
    val times = (0 until FFTLength / 2 by pow(2, k).toInt)
      .map(i => (i * 2 * Pi) / FFTLength.toDouble)
    val inits = times.map(t => FixedPoint.fromDouble(sin(t), DataWidth.W, BinaryPoint.BP))
    VecInit(inits)
  }
  
  def cosTable2(k: Int): Vec[FixedPoint] = {
    val times = (0 until FFTLength / 2 by pow(2, k).toInt)
      .map(i => (i * 2 * Pi) / FFTLength.toDouble)
    val inits = times.map(t => FixedPoint.fromDouble(cos(t), DataWidth.W, BinaryPoint.BP))
    VecInit(inits)
  }
  
  def wnTable(k: Int)(idx: UInt): MyComplex = {
    val res = Wire(new MyComplex)
    res.re := Mux(mode, cosTable2(k)(idx), cosTable(k)(idx))
    res.im := Mux(mode, sinTable2(k)(idx), sinTable(k)(idx))
    res
  }
  
  def timesInvn(a: MyComplex): MyComplex = {
    val b = Wire(new MyComplex)
    b.re := a.re >> stages
    b.im := a.im >> stages
    b
  }
  
  // R2MDC需要额外的流水线延迟：FFTLength * 3/2 - 1个周期
  val maxCount = (FFTLength * 3 / 2 - 1).asUInt()
  val cnt = RegInit(0.U((stages + 1).W))
  val busy = cnt =/= 0.U
  when(io.din_valid || busy){
    cnt := Mux(cnt === maxCount, 0.U, cnt + 1.U)
  }
  io.busy := busy
  
  val out1 = VecInit(Seq.fill(stages + 1)(0.S((2 * DataWidth).W).asTypeOf(new MyComplex)))
  val out2 = VecInit(Seq.fill(stages + 1)(0.S((2 * DataWidth).W).asTypeOf(new MyComplex)))
  out1(0) := io.dIn
  out2(0) := io.dIn
  
  for (i <- 0 until stages - 1) {
    val wnCtrl = cnt(stages-2-i, 0)
    val wn = wnTable(i)(wnCtrl)
    val BF12 = Butterfly(ShiftRegister(out1(i), (FFTLength / pow(2, i + 1)).toInt), out2(i), wn)
    val swCtrl = cnt(stages-2-i)
    val sw12 = Switch(BF12._1, ShiftRegister(BF12._2, (FFTLength / pow(2, i + 2)).toInt), swCtrl)
    out1(i + 1) := sw12._1
    out2(i + 1) := sw12._2
  }
  
  val out1D1 = RegNext(out1(stages - 1))
  out1(stages) := ComplexAdd(out1D1, out2(stages - 1))
  out2(stages) := ComplexSub(out1D1, out2(stages - 1))
  val dout1 = Mux(mode, timesInvn(out1(stages)), out1(stages))
  val dout2 = Mux(mode, timesInvn(out2(stages)), out2(stages))
  
  io.dOut1 := RegNext(dout1)
  io.dOut2 := RegNext(dout2)
  io.dout_valid := RegNext(cnt) === (FFTLength - 1).asUInt()
}

// 优化的R2MDC算法核心模块 (之前错误地命名为CooleyTukey)
class OptimizedR2MDCCore extends Module with HasDataConfig with HasElaborateConfig {
  val io = IO(new FFTCoreIO)
  
  val mode = io.mode.getOrElse(false.B)
  val stages = log2Ceil(FFTLength)
  
  
  // 修改旋转因子计算，确保正确的索引和值
  def sinTable(k: Int): Vec[FixedPoint] = {
    val times = (0 until FFTLength / 2 by pow(2, k).toInt)
      .map(i => -(i * 2 * Pi) / FFTLength.toDouble)
    val inits = times.map(t => FixedPoint.fromDouble(sin(t), DataWidth.W, BinaryPoint.BP))
    VecInit(inits)
  }
  
  def cosTable(k: Int): Vec[FixedPoint] = {
    val times = (0 until FFTLength / 2 by pow(2, k).toInt)
      .map(i => -(i * 2 * Pi) / FFTLength.toDouble)
    val inits = times.map(t => FixedPoint.fromDouble(cos(t), DataWidth.W, BinaryPoint.BP))
    VecInit(inits)
  }
  
  def sinTable2(k: Int): Vec[FixedPoint] = {
    val times = (0 until FFTLength / 2 by pow(2, k).toInt)
      .map(i => (i * 2 * Pi) / FFTLength.toDouble)
    val inits = times.map(t => FixedPoint.fromDouble(sin(t), DataWidth.W, BinaryPoint.BP))
    VecInit(inits)
  }
  
  def cosTable2(k: Int): Vec[FixedPoint] = {
    val times = (0 until FFTLength / 2 by pow(2, k).toInt)
      .map(i => (i * 2 * Pi) / FFTLength.toDouble)
    val inits = times.map(t => FixedPoint.fromDouble(cos(t), DataWidth.W, BinaryPoint.BP))
    VecInit(inits)
  }
  
  def wnTable(k: Int)(idx: UInt): MyComplex = {
    val res = Wire(new MyComplex)
    res.re := Mux(mode, cosTable2(k)(idx), cosTable(k)(idx))
    res.im := Mux(mode, sinTable2(k)(idx), sinTable(k)(idx))
    res
  }
  
  def timesInvn(a: MyComplex): MyComplex = {
    val b = Wire(new MyComplex)
    b.re := a.re >> stages
    b.im := a.im >> stages
    b
  }
  
  // CooleyTukey只需要FFTLength个周期来加载数据（批处理模式）
  val maxCount = (FFTLength - 1).asUInt()
  val cnt = RegInit(0.U((stages + 1).W))
  val busy = cnt =/= 0.U
  when(io.din_valid || busy) {
    cnt := Mux(cnt === maxCount, 0.U, cnt + 1.U)
  }
  io.busy := busy
  
  // 使用标准位宽
  val out1 = VecInit(Seq.fill(stages + 1)(0.S((2 * DataWidth).W).asTypeOf(new MyComplex)))
  val out2 = VecInit(Seq.fill(stages + 1)(0.S((2 * DataWidth).W).asTypeOf(new MyComplex)))

  // 直接使用输入数据
  out1(0) := io.dIn
  out2(0) := io.dIn
  for (i <- 0 until stages - 1) {
    // 使用与R2MDC相同的控制信号
    val wnCtrl = cnt(stages-2-i, 0)
    val wn = wnTable(i)(wnCtrl)
    
    val delay1 = (FFTLength / pow(2, i + 1)).toInt
    val delay2 = (FFTLength / pow(2, i + 2)).toInt
    
    val delayedOut1 = ShiftRegister(out1(i), delay1)
    val BF12 = Butterfly(delayedOut1, out2(i), wn)
    
    // 移除溢出检测和饱和处理，保持简单
    
    val swCtrl = cnt(stages-2-i)
    val delayedBF2 = ShiftRegister(BF12._2, delay2)
    val sw12 = Switch(BF12._1, delayedBF2, swCtrl)

    // 在前几级应用温和的缩放以防止溢出
    // 只在前4级进行缩放，每2级右移1位
    if (i < 4 && i % 2 == 1) {
      val scaled1 = Wire(new MyComplex)
      val scaled2 = Wire(new MyComplex)
      scaled1.re := sw12._1.re >> 1
      scaled1.im := sw12._1.im >> 1
      scaled2.re := sw12._2.re >> 1
      scaled2.im := sw12._2.im >> 1
      out1(i + 1) := scaled1
      out2(i + 1) := scaled2
    } else {
      out1(i + 1) := sw12._1
      out2(i + 1) := sw12._2
    }
  }
  
  val out1D1 = RegNext(out1(stages - 1))
  out1(stages) := ComplexAdd(out1D1, out2(stages - 1))
  out2(stages) := ComplexSub(out1D1, out2(stages - 1))
  
  // 补偿前4级中第1和第3级的缩放（共右移2位）
  val compensated1 = Wire(new MyComplex)
  val compensated2 = Wire(new MyComplex)
  compensated1.re := out1(stages).re << 2
  compensated1.im := out1(stages).im << 2
  compensated2.re := out2(stages).re << 2
  compensated2.im := out2(stages).im << 2

  val dout1 = Mux(mode, timesInvn(compensated1), compensated1)
  val dout2 = Mux(mode, timesInvn(compensated2), compensated2)

  io.dOut1 := RegNext(dout1)
  io.dOut2 := RegNext(dout2)
  io.dout_valid := RegNext(cnt) === (FFTLength - 1).asUInt()
}

// 为了保持兼容，保留CooleyTukeyCore作为OptimizedR2MDCCore的别名
class CooleyTukeyCore extends OptimizedR2MDCCore

// R2CSS算法核心模块 - Radix-2 Single-path Delay Feedback (R2SDF)
// 为了与批处理测试框架兼容，本实现采用与R2MDC相同的算法核心
class R2CSSCore extends R2MDCCore

// R2DIF算法核心模块 - Radix-2 Decimation-In-Frequency
// 使用SyncReadMem确保BRAM推断，简单批处理架构
class R2DIFCore extends Module with HasDataConfig with HasElaborateConfig {
  val io = IO(new FFTCoreIO)

  val mode = io.mode.getOrElse(false.B)
  val stages = log2Ceil(FFTLength)

  // DIF旋转因子表
  def difSinTable(stage: Int): Vec[FixedPoint] = {
    val step = pow(2, stage).toInt
    val numEntries = FFTLength / (2 * step)
    val times = (0 until numEntries).map(i => -(i * step * 2 * Pi) / FFTLength.toDouble)
    val inits = times.map(t => FixedPoint.fromDouble(sin(t), DataWidth.W, BinaryPoint.BP))
    VecInit(inits)
  }

  def difCosTable(stage: Int): Vec[FixedPoint] = {
    val step = pow(2, stage).toInt
    val numEntries = FFTLength / (2 * step)
    val times = (0 until numEntries).map(i => -(i * step * 2 * Pi) / FFTLength.toDouble)
    val inits = times.map(t => FixedPoint.fromDouble(cos(t), DataWidth.W, BinaryPoint.BP))
    VecInit(inits)
  }

  def difSinTable2(stage: Int): Vec[FixedPoint] = {
    val step = pow(2, stage).toInt
    val numEntries = FFTLength / (2 * step)
    val times = (0 until numEntries).map(i => (i * step * 2 * Pi) / FFTLength.toDouble)
    val inits = times.map(t => FixedPoint.fromDouble(sin(t), DataWidth.W, BinaryPoint.BP))
    VecInit(inits)
  }

  def difCosTable2(stage: Int): Vec[FixedPoint] = {
    val step = pow(2, stage).toInt
    val numEntries = FFTLength / (2 * step)
    val times = (0 until numEntries).map(i => (i * step * 2 * Pi) / FFTLength.toDouble)
    val inits = times.map(t => FixedPoint.fromDouble(cos(t), DataWidth.W, BinaryPoint.BP))
    VecInit(inits)
  }

  def timesInvn(a: MyComplex): MyComplex = {
    val b = Wire(new MyComplex)
    b.re := a.re >> stages
    b.im := a.im >> stages
    b
  }

  // Bit-reverse函数：用于R2DIF输出重排序
  def bitReverse(index: UInt, width: Int): UInt = {
    val bits = Wire(Vec(width, Bool()))
    for (i <- 0 until width) {
      bits(i) := index(width - 1 - i)
    }
    bits.asUInt
  }

  // 使用Chisel Mem（异步读，Vivado会推断为BRAM）
  // 确保每周期只有一次写操作，满足BRAM推断要求
  val inputBuffer = Mem(FFTLength, new MyComplex)
  val bufferA = Mem(FFTLength, new MyComplex)
  val bufferB = Mem(FFTLength, new MyComplex)

  // 简单状态机：sInput -> sProcess -> sOutput
  val sInput :: sProcess :: sOutput :: Nil = Enum(3)
  val state = RegInit(sInput)

  val inputCnt = RegInit(0.U(log2Ceil(FFTLength + 1).W))
  val currentStage = RegInit(0.U(log2Ceil(stages + 1).W))
  val butterflyCnt = RegInit(0.U(log2Ceil(FFTLength + 1).W))

  // 蝶形处理子状态：5状态完全串行，满足BRAM约束
  // sReadUpper(读upper) -> sReadLower(读lower) -> sCompute(计算) -> sWriteUpper(写upper) -> sWriteLower(写lower)
  val sReadUpper :: sReadLower :: sCompute :: sWriteUpper :: sWriteLower :: Nil = Enum(5)
  val bfState = RegInit(sReadUpper)

  // 保存数据和地址
  val savedDataUpper = Reg(new MyComplex)  // 保存upper数据
  val savedDataLower = Reg(new MyComplex)  // 保存lower数据
  val savedBfOut1 = Reg(new MyComplex)     // 保存upper结果
  val savedBfOut2 = Reg(new MyComplex)     // 保存lower结果
  val savedUpperAddr = RegInit(0.U(log2Ceil(FFTLength).W))
  val savedLowerAddr = RegInit(0.U(log2Ceil(FFTLength).W))
  val savedIsEvenStage = RegInit(false.B)
  val savedInBlockIdx = RegInit(0.U(log2Ceil(FFTLength).W))
  val savedUseInputBuffer = RegInit(false.B)

  // 地址计算函数
  def getBlockIdx(cnt: UInt, stage: UInt): UInt = {
    cnt >> (stages.U - stage - 1.U)
  }

  def getInBlockIdx(cnt: UInt, stage: UInt): UInt = {
    cnt & ((1.U << (stages.U - stage - 1.U)) - 1.U)
  }

  // 状态机
  switch(state) {
    is(sInput) {
      when(io.din_valid) {
        inputBuffer.write(inputCnt, io.dIn)
        inputCnt := inputCnt + 1.U
        when(inputCnt === (FFTLength - 1).U) {
          state := sProcess
          inputCnt := 0.U
          currentStage := 0.U
          butterflyCnt := 0.U
          bfState := sReadUpper  // 从读upper开始
        }
      }
    }

    is(sProcess) {
      when(currentStage < stages.U) {
        // 5状态机：完全串行，每周期只有1次读或写
        when(bfState === sReadUpper) {
          // 状态1：发起upper读取
          val blockIdx = getBlockIdx(butterflyCnt, currentStage)
          val inBlockIdx = getInBlockIdx(butterflyCnt, currentStage)
          val blockSize = 1.U << (stages.U - currentStage)
          val upperAddr = blockIdx * blockSize + inBlockIdx
          val lowerAddr = blockIdx * blockSize + inBlockIdx + (blockSize >> 1)

          val isEvenStage = (currentStage & 1.U) === 0.U
          val useInputBuffer = (currentStage === 0.U)

          // 保存地址和控制信息
          savedUpperAddr := upperAddr
          savedLowerAddr := lowerAddr
          savedIsEvenStage := isEvenStage
          savedInBlockIdx := inBlockIdx
          savedUseInputBuffer := useInputBuffer

          // 本周期不进行任何读写（Mem会在下一状态读取）
          bfState := sReadLower

        }.elsewhen(bfState === sReadLower) {
          // 状态2：读取upper数据（上周期计算的地址），准备读lower
          // Mem是异步读，直接读取
          val dataUpper = Mux(savedUseInputBuffer,
                           inputBuffer.read(savedUpperAddr),
                           Mux(savedIsEvenStage, bufferA.read(savedUpperAddr), bufferB.read(savedUpperAddr)))

          savedDataUpper := dataUpper
          bfState := sCompute

        }.elsewhen(bfState === sCompute) {
          // 状态3：读取lower数据并执行蝶形计算
          val dataLower = Mux(savedUseInputBuffer,
                           inputBuffer.read(savedLowerAddr),
                           Mux(savedIsEvenStage, bufferA.read(savedLowerAddr), bufferB.read(savedLowerAddr)))

          savedDataLower := dataLower

          // 获取旋转因子
          val wnRe = MuxLookup(currentStage, 0.S(32.W).asFixedPoint(BinaryPoint.BP),
            (0 until stages).map(s => s.U -> Mux(mode, difCosTable2(s)(savedInBlockIdx), difCosTable(s)(savedInBlockIdx))))
          val wnIm = MuxLookup(currentStage, 0.S(32.W).asFixedPoint(BinaryPoint.BP),
            (0 until stages).map(s => s.U -> Mux(mode, difSinTable2(s)(savedInBlockIdx), difSinTable(s)(savedInBlockIdx))))

          val wn = Wire(new MyComplex)
          wn.re := wnRe
          wn.im := wnIm

          // DIF蝶形运算
          val bfOut1 = ComplexAdd(savedDataUpper, dataLower)
          val bfOut2 = ComplexMul(ComplexSub(savedDataUpper, dataLower), wn)

          savedBfOut1 := bfOut1
          savedBfOut2 := bfOut2

          bfState := sWriteUpper

        }.elsewhen(bfState === sWriteUpper) {
          // 状态4：写入upper结果
          when(savedIsEvenStage) {
            bufferB.write(savedUpperAddr, savedBfOut1)
          }.otherwise {
            bufferA.write(savedUpperAddr, savedBfOut1)
          }

          bfState := sWriteLower

        }.elsewhen(bfState === sWriteLower) {
          // 状态5：写入lower结果
          when(savedIsEvenStage) {
            bufferB.write(savedLowerAddr, savedBfOut2)
          }.otherwise {
            bufferA.write(savedLowerAddr, savedBfOut2)
          }

          // 更新蝶形计数器
          butterflyCnt := butterflyCnt + 1.U
          when(butterflyCnt === ((FFTLength / 2) - 1).U) {
            butterflyCnt := 0.U
            currentStage := currentStage + 1.U
          }

          bfState := sReadUpper
        }
      }.otherwise {
        state := sOutput
        inputCnt := 0.U
        bfState := sReadUpper
      }
    }

    is(sOutput) {
      // Mem异步读输出：每周期输出2个数据
      inputCnt := inputCnt + 2.U
      when(inputCnt >= (FFTLength - 2).U) {
        state := sInput
        inputCnt := 0.U
      }
    }
  }

  // 输出逻辑：R2DIF输出是bit-reversed顺序（按自然顺序读取即可）
  // Mem异步读，数据立即可用
  val finalBuffer = if (stages % 2 == 1) bufferB else bufferA

  // 读取当前地址的数据（Mem异步读）
  val outData1 = finalBuffer.read(inputCnt)
  val outData2 = finalBuffer.read(inputCnt + 1.U)

  io.dOut1 := Mux(mode, timesInvn(outData1), outData1)
  io.dOut2 := Mux(mode, timesInvn(outData2), outData2)
  io.dout_valid := state === sOutput
  io.busy := state =/= sInput
}
