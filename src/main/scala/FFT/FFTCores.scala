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
