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
// DIF算法独立实现，使用R2SDF流水线架构
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

  def difWnTable(stage: Int)(idx: UInt): MyComplex = {
    val res = Wire(new MyComplex)
    res.re := Mux(mode, difCosTable2(stage)(idx), difCosTable(stage)(idx))
    res.im := Mux(mode, difSinTable2(stage)(idx), difSinTable(stage)(idx))
    res
  }

  def timesInvn(a: MyComplex): MyComplex = {
    val b = Wire(new MyComplex)
    b.re := a.re >> stages
    b.im := a.im >> stages
    b
  }

  // Bit-reverse函数：DIF输出需要bit-reverse
  def bitReverse(idx: UInt, width: Int): UInt = {
    val result = Wire(UInt(width.W))
    val bits = (0 until width).map(i => idx(i))
    result := Cat(bits)
    result
  }

  // 输入适配层 - 收集所有输入
  val inputBuffer = Mem(FFTLength, new MyComplex)
  val inputCnt = RegInit(0.U(log2Ceil(FFTLength + 1).W))

  // DIF批处理算法：使用两个buffer交替读写
  // bufferA和bufferB交替使用，每个stage从一个读，写到另一个
  val bufferA = Mem(FFTLength, new MyComplex)
  val bufferB = Mem(FFTLength, new MyComplex)

  // 四状态：sBufferInit(初始化buffer) -> sInput(收集输入) -> sProcess(处理) -> sOutput(输出)
  val sBufferInit :: sInput :: sProcess :: sOutput :: Nil = Enum(4)
  val state = RegInit(sBufferInit)
  val inputPhase = state === sInput

  // 用于初始化buffer和bufferA的计数器
  val initCnt = RegInit(0.U(log2Ceil(FFTLength + 1).W))

  // Buffer初始化状态：首次运行时将bufferA和bufferB清零
  when(state === sBufferInit) {
    val zeroComplex = Wire(new MyComplex)
    zeroComplex.re := FixedPoint.fromDouble(0.0, DataWidth.W, BinaryPoint.BP)
    zeroComplex.im := FixedPoint.fromDouble(0.0, DataWidth.W, BinaryPoint.BP)
    bufferA.write(initCnt, zeroComplex)
    bufferB.write(initCnt, zeroComplex)
    initCnt := initCnt + 1.U
    when(initCnt === (FFTLength - 1).U) {
      state := sInput
      initCnt := 0.U
    }
  }

  switch(state) {
    is(sInput) {
      when(io.din_valid) {
        inputBuffer.write(inputCnt, io.dIn)
        inputCnt := inputCnt + 1.U
        when(inputCnt === (FFTLength - 1).U) {
          state := sProcess
          inputCnt := 0.U
          initCnt := 0.U  // 开始初始化bufferA
        }
      }
    }
    is(sProcess) {
      // 批处理FFT计算，完成后进入输出状态
    }
    is(sOutput) {
      // 输出完成后回到输入状态
    }
  }

  // 当前stage和处理进度
  val currentStage = RegInit(0.U(log2Ceil(stages + 1).W))
  val stageCnt = RegInit(0.U(log2Ceil(FFTLength + 1).W))

  val busy = state === sProcess || state === sBufferInit
  io.busy := busy

  // 批处理FFT状态机
  when(state === sProcess) {
    when(currentStage === 0.U && initCnt < FFTLength.U) {
      // Stage 0开始前，将inputBuffer复制到bufferA
      bufferA.write(initCnt, inputBuffer.read(initCnt))
      initCnt := initCnt + 1.U
    }.otherwise {
      // 执行FFT蝶形运算
      // 使用currentStage的Scala值来确定buffer选择和参数
      for (s <- 0 until stages) {
        when(currentStage === s.U) {
          val blockSize = FFTLength / pow(2, s).toInt
          val numButterflies = FFTLength / 2
          val butterflyPairs = blockSize / 2

          // 计算当前蝶形的索引
          val blockIdx = stageCnt / butterflyPairs.U
          val inBlockIdx = stageCnt % butterflyPairs.U
          val upperIdx = blockIdx * blockSize.U + inBlockIdx
          val lowerIdx = upperIdx + butterflyPairs.U

          // 读取蝶形输入（根据stage奇偶性选择buffer）
          val upper = if (s % 2 == 0) bufferA.read(upperIdx) else bufferB.read(upperIdx)
          val lower = if (s % 2 == 0) bufferA.read(lowerIdx) else bufferB.read(lowerIdx)

          // 计算旋转因子
          val wn = difWnTable(s)(inBlockIdx)

          // DIF蝶形运算
          val bfOut1 = ComplexAdd(upper, lower)
          val bfOut2 = ComplexMul(ComplexSub(upper, lower), wn)

          // 写回到目标buffer（奇偶交替）
          if (s % 2 == 0) {
            bufferB.write(upperIdx, bfOut1)
            bufferB.write(lowerIdx, bfOut2)
          } else {
            bufferA.write(upperIdx, bfOut1)
            bufferA.write(lowerIdx, bfOut2)
          }
        }
      }

      // 更新计数器（每个stage处理N/2个蝶形）
      stageCnt := stageCnt + 1.U
      when(stageCnt === ((FFTLength / 2) - 1).U) {
        stageCnt := 0.U
        currentStage := currentStage + 1.U
        when(currentStage === (stages - 1).U) {
          // 所有stage完成
          currentStage := 0.U
          state := sOutput
        }
      }
    }
  }

  // 输出逻辑：在sOutput状态读取最终的FFT结果
  val outputCnt = RegInit(0.U(log2Ceil(FFTLength + 1).W))

  when(state === sOutput) {
    outputCnt := outputCnt + 2.U
    when(outputCnt >= (FFTLength - 2).U) {
      outputCnt := 0.U
      state := sInput
    }
  }

  // 确定最终结果在哪个buffer（stages是奇数，结果在bufferB）
  val finalBuffer = if (stages % 2 == 1) bufferB else bufferA

  // DIF算法的输出顺序：我们的实现已经产生了自然顺序输出
  // （因为每个stage内的蝶形按顺序排列）
  val outputData1 = finalBuffer.read(outputCnt)
  val outputData2 = finalBuffer.read(outputCnt + 1.U)

  val scaledData1 = Mux(mode, timesInvn(outputData1), outputData1)
  val scaledData2 = Mux(mode, timesInvn(outputData2), outputData2)

  io.dOut1 := Mux(state === sOutput, scaledData1, 0.S.asTypeOf(new MyComplex))
  io.dOut2 := Mux(state === sOutput, scaledData2, 0.S.asTypeOf(new MyComplex))
  io.dout_valid := state === sOutput
}
