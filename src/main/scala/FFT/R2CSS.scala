package FFT

import chisel3._
import chisel3.util._
import scala.math._

class R2CSSCommutator(dim: Int) extends Module with HasDataConfig {
  val io = IO(new Bundle {
    val in1 = Input(SInt(DataWidth.W))
    val in2 = Input(SInt(DataWidth.W))
    val s = Input(Bool())
    val out1 = Output(SInt(DataWidth.W))
    val out2 = Output(SInt(DataWidth.W))
  })
  val wire1 = Wire(SInt(DataWidth.W))
  val wire2 = Wire(SInt(DataWidth.W))
  wire1 := ShiftRegister(io.in2, dim)
  wire2 := Mux(io.s, wire1, io.in1)
  io.out1 := ShiftRegister(wire2, dim)
  io.out2 := Mux(io.s, io.in1, wire1)
}

object R2CSSCommutator {
  def apply(in1: SInt, in2: SInt, select: Bool, dim: Int): (SInt, SInt) = {
    val inst = Module(new R2CSSCommutator(dim))
    inst.io.in1 := in1
    inst.io.in2 := in2
    inst.io.s := select
    (inst.io.out1, inst.io.out2)
  }
}

class R2CSSRealAddSub extends Module with HasDataConfig {
  val io = IO(new Bundle {
    val a = Input(SInt(DataWidth.W))
    val b = Input(SInt(DataWidth.W))
    val A = Output(SInt(DataWidth.W))
    val B = Output(SInt(DataWidth.W))
  })
  io.A := io.a + io.b
  io.B := io.a - io.b
}

object R2CSSRealAddSub {
  def apply(in1: SInt, in2: SInt): (SInt, SInt) = {
    val inst = Module(new R2CSSRealAddSub)
    inst.io.a := in1
    inst.io.b := in2
    (inst.io.A, inst.io.B)
  }
}

class R2CSSSwitch extends Module with HasDataConfig {
  val io = IO(new Bundle {
    val s = Input(Bool())
    val in1 = Input(SInt(DataWidth.W))
    val in2 = Input(SInt(DataWidth.W))
    val out1 = Output(SInt(DataWidth.W))
    val out2 = Output(SInt(DataWidth.W))
  })
  io.out1 := Mux(io.s, io.in2, io.in1)
  io.out2 := Mux(io.s, io.in1, io.in2)
}

object R2CSSSwitch {
  def apply(in1: SInt, in2: SInt, select: Bool): (SInt, SInt) = {
    val inst = Module(new R2CSSSwitch)
    inst.io.in1 := in1
    inst.io.in2 := in2
    inst.io.s := select
    (inst.io.out1, inst.io.out2)
  }
}

class R2CSSRealAdder extends Module with HasDataConfig {
  val io = IO(new Bundle {
    val in1 = Input(SInt(DataWidth.W))
    val in2 = Input(SInt(DataWidth.W))
    val s = Input(Bool())
    val out = Output(SInt(DataWidth.W))
  })
  io.out := Mux(io.s, io.in1 - io.in2, io.in1 + io.in2)
}

object R2CSSRealAdder {
  def apply(in1: SInt, in2: SInt, select: Bool): SInt = {
    val inst = Module(new R2CSSRealAdder)
    inst.io.in1 := in1
    inst.io.in2 := in2
    inst.io.s := select
    inst.io.out
  }
}

class R2CSSRealBranchMul extends Module with HasDataConfig {
  val io = IO(new Bundle {
    val dataIn = Input(SInt(DataWidth.W))
    val w_re_in = Input(SInt(DataWidth.W))
    val w_im_in = Input(SInt(DataWidth.W))
    val out1 = Output(SInt(DataWidth.W))
    val out2 = Output(SInt(DataWidth.W))
  })

  private def qmul(a: SInt, b: SInt): SInt = {
    val prod = (a * b).asSInt
    (prod >> BinaryPoint).asSInt
  }

  io.out1 := qmul(io.dataIn, io.w_re_in)
  io.out2 := qmul(io.dataIn, io.w_im_in)
}

object R2CSSRealBranchMul {
  def apply(dataInput: SInt, wRe: SInt, wIm: SInt): (SInt, SInt) = {
    val inst = Module(new R2CSSRealBranchMul)
    inst.io.dataIn := dataInput
    inst.io.w_re_in := wRe
    inst.io.w_im_in := wIm
    (inst.io.out1, inst.io.out2)
  }
}

class R2CSSSDFUnit extends Module with HasDataConfig with HasElaborateConfig {
  val io = IO(new Bundle {
    val in = Input(new MyComplex)
    val ctrlSub = Input(Bool())
    val ctrlAdd = Input(Bool())
    val out = Output(new MyComplex)
    val altRst = Input(Bool())
  })

  val wire1 = Wire(new MyComplex)
  val wire2 = Wire(new MyComplex)
  chisel3.withReset(io.altRst) {
    wire1 := Mux(io.ctrlAdd, wire2, ComplexAdd(wire2, io.in))
    io.out := Mux(io.ctrlSub, wire2, ComplexSub(wire2, io.in))
    wire2 := ShiftRegister(
      wire1,
      FFTLength / 2,
      0.S((2 * DataWidth).W).asTypeOf(new MyComplex),
      true.B
    )
  }
}

object R2CSSSDFUnit {
  def apply(input: MyComplex, controlSub: Bool, controlAdd: Bool, alternateReset: Bool): MyComplex = {
    val inst = Module(new R2CSSSDFUnit)
    inst.io.in := input
    inst.io.ctrlSub := controlSub
    inst.io.ctrlAdd := controlAdd
    inst.io.altRst := alternateReset
    inst.io.out
  }
}

class R2CSSCore extends Module with HasDataConfig with HasElaborateConfig {
  val io = IO(new FFTCoreIO)

  private val stageNum = log2Ceil(FFTLength) - 1
  private val totalCount = (FFTLength * 7 / 2) + stageNum - 1
  private val dCount = RegInit(0.U(log2Ceil(totalCount + 1).W))
  private val busy = dCount =/= 0.U
  when(io.din_valid || busy) {
    dCount := Mux(dCount === totalCount.U, 0.U, dCount + 1.U)
  }
  io.busy := busy

  private val scale = 1L << BinaryPoint
  private val maxVal = (1L << (DataWidth - 1)) - 1
  private val minVal = -(1L << (DataWidth - 1))
  private def fixedSInt(value: Double): SInt = {
    val raw = math.round(value * scale)
    val clamped = math.max(minVal, math.min(maxVal, raw)).toLong
    clamped.S(DataWidth.W)
  }

  private val twiddleTimes = (0 until (FFTLength / 2)).map(i => 2 * Pi * i / FFTLength.toDouble)
  private val twiddleRe = VecInit(twiddleTimes.map(t => fixedSInt(cos(t))))
  private val twiddleIm = VecInit(twiddleTimes.map(t => fixedSInt(-sin(t))))

  private def W(index: UInt): (SInt, SInt) = (twiddleRe(index), twiddleIm(index))

  private def WIndexVec(s_i: Int): Vec[UInt] = {
    val step = pow(2, s_i).toInt
    val base = (0 until FFTLength / 2 by step).toVector
    val even = base.indices.filter(_ % 2 == 0)
    val odd = base.indices.filter(_ % 2 == 1)
    var repTwice = Vector[Int]()
    for (i <- even ++ odd) {
      repTwice = repTwice ++ Vector(i, i)
    }
    val raw = repTwice.map(base)
    val (rawHead, rawTail) = raw.splitAt(raw.length / 2)
    val headRep = Vector.fill(step)(rawHead).flatten
    val tailRep = Vector.fill(step)(rawTail).flatten
    VecInit((headRep ++ tailRep).map(i => i.U))
  }

  val stageIntf1 = Wire(Vec(stageNum + 1, SInt(DataWidth.W)))
  val stageIntf2 = Wire(Vec(stageNum + 1, SInt(DataWidth.W)))
  stageIntf1.foreach(_ := 0.S)
  stageIntf2.foreach(_ := 0.S)

  val preComm = R2CSSCommutator(io.dIn.re, io.dIn.im, dCount(0), 1)
  stageIntf1(0) := preComm._1
  stageIntf2(0) := preComm._2

  for (s_i <- 0 until stageNum) {
    val ws = s_i + 1 + FFTLength * (1 - pow(2, -s_i - 1))
    val wsU = round(ws).toInt
    val we = round(ws + FFTLength).toInt
    val wStart = wsU.U
    val wEnd = we.U
    val wReturn = W(WIndexVec(s_i)(dCount - wStart))
    val wireWRe = Wire(SInt(DataWidth.W))
    val wireWIm = Wire(SInt(DataWidth.W))
    wireWRe := Mux(dCount >= wStart && dCount < wEnd, wReturn._1, 0.S(DataWidth.W))
    wireWIm := Mux(dCount >= wStart && dCount < wEnd, wReturn._2, 0.S(DataWidth.W))

    val dcDim = round(FFTLength / pow(2, s_i + 1)).toInt
    val wireSelectDC = Mux(
      dCount >= wStart,
      !((dCount - wStart)(log2Ceil(dcDim))),
      false.B
    )

    val dataCom = R2CSSCommutator(stageIntf1(s_i), stageIntf2(s_i), wireSelectDC, dcDim)
    val addSub = R2CSSRealAddSub(dataCom._1, dataCom._2)
    val realMult = R2CSSRealBranchMul(addSub._2, wireWRe, wireWIm)

    val commonSel = if (s_i % 2 == 0) !dCount(0) else dCount(0)
    val swReturn = R2CSSSwitch(realMult._1, realMult._2, commonSel)
    val commDim1 = R2CSSCommutator(swReturn._1, swReturn._2, commonSel, 1)
    stageIntf2(s_i + 1) := R2CSSRealAdder(commDim1._1, commDim1._2, commonSel)
    stageIntf1(s_i + 1) := RegNext(addSub._1)
  }

  val postSel = if (stageNum % 2 == 0) !dCount(0) else dCount(0)
  val postStage = R2CSSCommutator(stageIntf1(stageNum), stageIntf2(stageNum), postSel, 1)
  val wireFpToComp = Wire(new MyComplex)
  wireFpToComp.re := postStage._1
  wireFpToComp.im := postStage._2

  val addWindow = dCount >= (FFTLength + stageNum).U && dCount < (2 * FFTLength + stageNum).U
  val subWindow = dCount >= (FFTLength * 3 / 2 + stageNum).U && dCount < (2 * FFTLength + stageNum).U
  val sdfReset = dCount === (FFTLength * 5 / 2 + stageNum - 1).U
  val sdfReturn = R2CSSSDFUnit(wireFpToComp, !subWindow, !addWindow, sdfReset)

  io.dOut1 := sdfReturn
  io.dOut2 := 0.S((2 * DataWidth).W).asTypeOf(new MyComplex)
  io.dout_valid := RegNext(dCount) === (FFTLength * 3 / 2 + stageNum - 1).U
}
