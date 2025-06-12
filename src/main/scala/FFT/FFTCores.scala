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
  
  val cnt = RegInit(0.U((stages + 1).W))
  val busy = cnt =/= 0.U
  when(io.din_valid || busy){
    cnt := Mux(cnt === (FFTLength * 3 / 2 - 1).asUInt(), 0.U, cnt + 1.U)
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

// Cooley-Tukey算法核心模块
class CooleyTukeyCore extends Module with HasDataConfig with HasElaborateConfig {
  val io = IO(new FFTCoreIO)
  
  val mode = io.mode.getOrElse(false.B)
  val stages = log2Ceil(FFTLength)
  
  // 添加调试信号
  val debug_stage = RegInit(0.U(log2Ceil(stages).W))
  val debug_overflow = RegInit(false.B)
  val debug_input_re = RegInit(0.S(DataWidth.W))
  val debug_input_im = RegInit(0.S(DataWidth.W))
  val debug_output_re = RegInit(0.S(DataWidth.W))
  val debug_output_im = RegInit(0.S(DataWidth.W))
  
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
    // 修改索引计算，确保正确的旋转因子选择
    val tableIdx = idx % (FFTLength / pow(2, k + 1)).toInt.U
    res.re := Mux(mode, cosTable2(k)(tableIdx), cosTable(k)(tableIdx))
    res.im := Mux(mode, sinTable2(k)(tableIdx), sinTable(k)(tableIdx))
    res
  }
  
  def timesInvn(a: MyComplex): MyComplex = {
    val b = Wire(new MyComplex)
    b.re := a.re >> stages
    b.im := a.im >> stages
    b
  }
  
  val cnt = RegInit(0.U((stages + 1).W))
  val busy = cnt =/= 0.U
  when(io.din_valid || busy) {
    cnt := Mux(cnt === (FFTLength - 1).asUInt(), 0.U, cnt + 1.U)
  }
  io.busy := busy
  
  // 增加中间结果的位宽
  val out1 = VecInit(Seq.fill(stages + 1)(0.S((2 * DataWidth + 4).W).asTypeOf(new MyComplex)))
  val out2 = VecInit(Seq.fill(stages + 1)(0.S((2 * DataWidth + 4).W).asTypeOf(new MyComplex)))
  
  // 输入数据左移4位以增加精度
  val inputReg = RegNext(io.dIn)
  out1(0).re := inputReg.re << 4
  out1(0).im := inputReg.im << 4
  out2(0).re := inputReg.re << 4
  out2(0).im := inputReg.im << 4
  
  var overflowDetected = false.B
  
  for (i <- 0 until stages - 1) {
    // 修改控制信号计算
    val wnCtrl = (cnt >> (stages - 2 - i)) & ((FFTLength / pow(2, i + 1)).toInt - 1).U
    val wn = wnTable(i)(wnCtrl)
    
    // 打印旋转因子
    when(i.U === debug_stage) {
      printf("Stage %d: WN re=%d, im=%d\n", i.U, wn.re.asSInt, wn.im.asSInt)
    }
    
    val delay1 = (FFTLength / pow(2, i + 1)).toInt
    val delay2 = (FFTLength / pow(2, i + 2)).toInt
    
    val delayedOut1 = ShiftRegister(out1(i), delay1)
    val BF12 = Butterfly(delayedOut1, out2(i), wn)
    
    // 检查溢出
    val bfOverflow = BF12._1.re.asSInt > ((BigInt(1) << (DataWidth + 3)) - 1).S ||
                    BF12._1.re.asSInt < (-(BigInt(1) << (DataWidth + 3))).S ||
                    BF12._1.im.asSInt > ((BigInt(1) << (DataWidth + 3)) - 1).S ||
                    BF12._1.im.asSInt < (-(BigInt(1) << (DataWidth + 3))).S
    overflowDetected = overflowDetected || bfOverflow
    
    when(bfOverflow) {
      printf("Overflow detected at stage %d: re=%d, im=%d\n", 
             i.U, BF12._1.re.asSInt, BF12._1.im.asSInt)
    }
    
    val swCtrl = cnt(stages-2-i)
    val delayedBF2 = ShiftRegister(BF12._2, delay2)
    val sw12 = Switch(BF12._1, delayedBF2, swCtrl)
    
    val stageReg1 = RegNext(sw12._1)
    val stageReg2 = RegNext(sw12._2)
    
    out1(i + 1) := stageReg1
    out2(i + 1) := stageReg2
    
    // 打印中间结果
    when(i.U === debug_stage) {
      printf("Stage %d result: re=%d, im=%d\n", 
             i.U, stageReg1.re.asSInt, stageReg1.im.asSInt)
    }
  }
  
  val out1D1 = RegNext(out1(stages - 1))
  val out1D2 = RegNext(out1D1)
  val out2D1 = RegNext(out2(stages - 1))
  val out2D2 = RegNext(out2D1)
  
  out1(stages) := ComplexAdd(out1D2, out2D2)
  out2(stages) := ComplexSub(out1D2, out2D2)
  
  // 输出时右移4位以恢复原始精度
  val dout1 = Mux(mode, timesInvn(out1(stages)), out1(stages))
  val dout2 = Mux(mode, timesInvn(out2(stages)), out2(stages))
  
  val out1Reg1 = RegNext(dout1)
  val out1Reg2 = RegNext(out1Reg1)
  val out1Reg3 = RegNext(out1Reg2)
  val out2Reg1 = RegNext(dout2)
  val out2Reg2 = RegNext(out2Reg1)
  val out2Reg3 = RegNext(out2Reg2)
  
  // 输出时右移4位
  io.dOut1.re := out1Reg3.re >> 4
  io.dOut1.im := out1Reg3.im >> 4
  io.dOut2.re := out2Reg3.re >> 4
  io.dOut2.im := out2Reg3.im >> 4
  
  debug_output_re := out1Reg3.re.asSInt
  debug_output_im := out1Reg3.im.asSInt
  
  when(io.dout_valid) {
    printf("Output: re=%d, im=%d\n", 
           io.dOut1.re.asSInt, io.dOut1.im.asSInt)
  }
  
  debug_overflow := overflowDetected
  io.dout_valid := RegNext(cnt) === (FFTLength - 1).asUInt()
}
