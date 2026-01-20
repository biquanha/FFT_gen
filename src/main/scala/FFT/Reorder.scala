
package FFT

import chisel3._
import chisel3.experimental._
import chisel3.util._
import scala.math._

class Reorder extends Module with HasDataConfig with HasElaborateConfig{ //整理倒位序数据成正序数据
  val io = IO(new Bundle{
    val in1 = Input(new MyComplex)
    val in2 = Input(new MyComplex)
    val in_valid = Input(Bool())
    val out = Output(new MyComplex)
    val out_valid = Output(Bool())
  })

  val rambank0 = Mem(FFTLength/2, new MyComplex)
  val rambank1 = Mem(FFTLength/2, new MyComplex)

  val times = log2Ceil(FFTLength / 2)
  val in_counter = RegInit(0.U(times.W))    //接收数据的次数，每次接受2数据
  val out_counter = RegInit(0.U((times + 1).W))   //输出数据的次数，每次输出1数据
  val index1 = Reverse(Cat(in_counter, 0.U(1.W)))  //位反序
  val index2 = Reverse(Cat(in_counter, 1.U(1.W)))  //周期0 就会有in_counter == 0，此时index1 = b000……00， index2 == b100……00.
  when(io.in_valid || in_counter =/= 0.U){                           //每次有效读入2数据
    in_counter := in_counter + 1.U
    rambank0.write(index1, io.in1)
    rambank1.write(index2, io.in2)
  }

  io.out_valid := (RegNext(in_counter) === (FFTLength / 2 - 1).asUInt) || (out_counter =/= 0.U)
  when(io.out_valid) {
    out_counter := out_counter + 1.U
  }

  when(out_counter < (FFTLength / 2).U) {
    io.out := rambank0.read(out_counter)
  }.otherwise{
    io.out := rambank1.read(out_counter)
  }
}

class ReorderSingle extends Module with HasDataConfig with HasElaborateConfig {
  val io = IO(new Bundle{
    val in = Input(new MyComplex)
    val in_valid = Input(Bool())
    val out = Output(new MyComplex)
    val out_valid = Output(Bool())
  })

  val ram = Mem(FFTLength, new MyComplex)
  val width = log2Ceil(FFTLength)
  val inCounter = RegInit(0.U((width - 1).W))
  val inNextBit = RegInit(0.U(1.W))
  val outCounter = RegInit(0.U(width.W))
  val index0 = Reverse(inCounter)
  val index1 = Reverse(inCounter) + (FFTLength / 2).U

  when(io.in_valid || (inCounter =/= 0.U || inNextBit =/= 0.U)) {
    ram.write(Mux(inNextBit === 0.U, index1, index0), io.in)
    inCounter := inCounter + 1.U
    when(inCounter === (FFTLength / 2 - 1).U) {
      inNextBit := inNextBit + 1.U
    }
  }

  io.out_valid := (RegNext(Cat(inNextBit, inCounter)) === (FFTLength - 1).U) || (outCounter =/= 0.U)
  when(io.out_valid) {
    outCounter := outCounter + 1.U
  }
  io.out := ram.read(outCounter)
}

class FFTReorder extends Module
  with HasDataConfig
  with HasElaborateConfig {
  val io = IO(new Bundle{
    val mode = if(supportIFFT) Some(Input(Bool())) else None
    val dIn = Input(new MyComplex)
    val din_valid = Input(Bool())
    val dOut = Output(new MyComplex)
    val dout_valid: Bool = Output(Bool())
    val busy = Output(Bool())
  })

  val fftblock: FFT = Module(new FFT)
  fftblock.io.dIn := io.dIn
  fftblock.io.din_valid := io.din_valid

  if (fftAlgorithm == FFTAlgorithm.R2CSS) {
    val reorderSingle = Module(new ReorderSingle)
    reorderSingle.io.in := fftblock.io.dOut1
    reorderSingle.io.in_valid := fftblock.io.dout_valid
    io.dOut := reorderSingle.io.out
    io.dout_valid := reorderSingle.io.out_valid
  } else {
    val reorderblock = Module(new Reorder)
    reorderblock.io.in1 := fftblock.io.dOut1
    reorderblock.io.in2 := fftblock.io.dOut2
    reorderblock.io.in_valid := fftblock.io.dout_valid
    io.dOut := reorderblock.io.out
    io.dout_valid := reorderblock.io.out_valid
  }
  io.busy := fftblock.io.busy
}
