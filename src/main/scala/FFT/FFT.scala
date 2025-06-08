package FFT

import chisel3._
import chisel3.experimental._
import chisel3.util._
import scala.math._

class FFT extends Module 
  with HasDataConfig 
  with HasElaborateConfig {
  
  // 调试 fftAlgorithm
  println(s"[DEBUG] FFT instantiated with fftAlgorithm = $fftAlgorithm")
  if (fftAlgorithm == null) {
    println("[ERROR] fftAlgorithm is null in HasElaborateConfig")
  }
  
  val io = IO(new Bundle {
    val mode = if(supportIFFT) Some(Input(Bool())) else None
    val dIn = Input(new MyComplex)
    val din_valid = Input(Bool())
    val dOut1 = Output(new MyComplex)
    val dOut2 = Output(new MyComplex)
    val dout_valid = Output(Bool())
    val busy = Output(Bool())
  })
  
  fftAlgorithm match {
    case FFTAlgorithm.R2MDC => {
      println("[DEBUG] Instantiating r2mdcCore")
      val r2mdcCore = Module(new R2MDCCore())
      r2mdcCore.io <> io
    }
    case FFTAlgorithm.CooleyTukey => {
      println("[DEBUG] Instantiating CooleyTukeyCore")
      val cooleyTukeyCore = Module(new CooleyTukeyCore())
      cooleyTukeyCore.io <> io
    }
    case _ => {
      println("[DEBUG] Falling back to default R2MDC")
      val r2mdcCore = Module(new R2MDCCore())
      r2mdcCore.io <> io
    }
  }
}