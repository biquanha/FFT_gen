
package FFT

import chisel3._
import chisel3.experimental._

class MyComplex extends Bundle with HasDataConfig {
  val re = SInt(DataWidth.W)
  val im = SInt(DataWidth.W)
}

class ComplexOperationIO extends Bundle {
  val op1 = Input(new MyComplex())
  val op2= Input(new MyComplex())
  val res = Output(new MyComplex())
}

class ComplexAdd extends Module {
  val io = IO(new ComplexOperationIO)
  io.res.re := io.op1.re + io.op2.re
  io.res.im := io.op1.im + io.op2.im
}
object ComplexAdd {
  def apply(op1: MyComplex, op2: MyComplex):MyComplex = {
    val inst = Module(new ComplexAdd)
    inst.io.op1 := op1
    inst.io.op2 := op2
    inst.io.res
  }
}

class ComplexSub extends Module {
  val io = IO(new ComplexOperationIO)
  io.res.re := io.op1.re - io.op2.re
  io.res.im := io.op1.im - io.op2.im
}
object ComplexSub {
  def apply(op1: MyComplex, op2: MyComplex):MyComplex = {
    val inst = Module(new ComplexSub)
    inst.io.op1 := op1
    inst.io.op2 := op2
    inst.io.res
  }
}

class ComplexMul extends Module with HasElaborateConfig with HasDataConfig {
  val io = IO(new ComplexOperationIO)
  // 定点Q格式乘法：结果右移BinaryPoint位
  def qmul(a: SInt, b: SInt): SInt = {
    val prod = (a * b).asSInt
    (prod >> BinaryPoint).asSInt
  }
  if (useGauss) {
    val k1 = qmul(io.op2.re, (io.op1.re + io.op1.im).asSInt)
    val k2 = qmul(io.op1.re, (io.op2.im - io.op2.re).asSInt)
    val k3 = qmul(io.op1.im, (io.op2.re + io.op2.im).asSInt)
    io.res.re := (k1 - k3).asSInt
    io.res.im := (k1 + k2).asSInt
  } else {
    // 单周期实现
    val rr = qmul(io.op1.re, io.op2.re)
    val ii = qmul(io.op1.im, io.op2.im)
    val ri = qmul(io.op1.re, io.op2.im)
    val ir = qmul(io.op1.im, io.op2.re)
    io.res.re := (rr - ii).asSInt
    io.res.im := (ri + ir).asSInt
  }
}

// 流水线化的复数乘法器（可选，用于高频设计）
class ComplexMulPipelined extends Module
  with HasElaborateConfig with HasDataConfig {
  val io = IO(new ComplexOperationIO)

  // 第1级：计算4个乘积
  val mult_rr = RegNext((io.op1.re * io.op2.re).asSInt)
  val mult_ii = RegNext((io.op1.im * io.op2.im).asSInt)
  val mult_ri = RegNext((io.op1.re * io.op2.im).asSInt)
  val mult_ir = RegNext((io.op1.im * io.op2.re).asSInt)

  // 第2级：计算最终结果
  io.res.re := ((mult_rr - mult_ii).asSInt >> BinaryPoint).asSInt
  io.res.im := ((mult_ri + mult_ir).asSInt >> BinaryPoint).asSInt
}
object ComplexMul {
  def apply(op1: MyComplex, op2: MyComplex):MyComplex = {
    val inst = Module(new ComplexMul)
    inst.io.op1 := op1
    inst.io.op2 := op2
    inst.io.res
  }
}

class ButterflyIO extends Bundle
  with HasDataConfig {
  val in1 = Input(new MyComplex())
  val in2 = Input(new MyComplex())
  val wn = Input(new MyComplex())
  val out1 = Output(new MyComplex())
  val out2 = Output(new MyComplex())
}

class Butterfly extends Module {
  val io = IO(new ButterflyIO())
  val add1 = ComplexAdd(io.in1, io.in2)
  val sub2 = ComplexSub(io.in1, io.in2)
  val mul2 = ComplexMul(sub2, io.wn)
  io.out1 := add1
  io.out2 := mul2
}
object Butterfly {
  def apply(in1: MyComplex, in2: MyComplex, wn: MyComplex): (MyComplex, MyComplex) = {
    val inst = Module(new Butterfly)
    inst.io.in1 := in1
    inst.io.in2 := in2
    inst.io.wn := wn
    (inst.io.out1, inst.io.out2)
  }
}

class Switch extends Module {
  val io = IO(new Bundle{
    val in1 = Input(new MyComplex)
    val in2 = Input(new MyComplex)
    val sel = Input(Bool())
    val out1 = Output(new MyComplex)
    val out2 = Output(new MyComplex)
  })
  io.out1 := Mux(io.sel, io.in2, io.in1)
  io.out2 := Mux(io.sel, io.in1, io.in2)
}
object Switch {
  def apply(in1: MyComplex, in2: MyComplex, sel: Bool): (MyComplex, MyComplex) = {
    val inst = Module(new Switch)
    inst.io.in1 := in1
    inst.io.in2 := in2
    inst.io.sel := sel
    (inst.io.out1, inst.io.out2)
  }
}

// DIF蝶形单元 - Decimation-In-Frequency
// DIF蝶形的数学公式：
// out1 = in1 + in2
// out2 = (in1 - in2) * wn
class ButterflyDIF extends Module {
  val io = IO(new ButterflyIO())

  val add_result = ComplexAdd(io.in1, io.in2)
  val sub_result = ComplexSub(io.in1, io.in2)
  val mul_result = ComplexMul(sub_result, io.wn)

  io.out1 := add_result
  io.out2 := mul_result
}

object ButterflyDIF {
  def apply(in1: MyComplex, in2: MyComplex, wn: MyComplex): (MyComplex, MyComplex) = {
    val inst = Module(new ButterflyDIF)
    inst.io.in1 := in1
    inst.io.in2 := in2
    inst.io.wn := wn
    (inst.io.out1, inst.io.out2)
  }
}
