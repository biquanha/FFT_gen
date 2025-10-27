package FFT

import circt.stage.ChiselStage

object elaborateFFT extends App {
  val target = sys.props.getOrElse("TARGET_DIR", "Verilog")
  ChiselStage.emitSystemVerilogFile(new FFT(), Array("--target-dir", target))
}

object elaborateFFTReorder extends App {
  val target = sys.props.getOrElse("TARGET_DIR", "Verilog")
  ChiselStage.emitSystemVerilogFile(new FFTReorder(), Array("--target-dir", target))
}

object elaborateTOP extends App {
  val target = sys.props.getOrElse("TARGET_DIR", "Verilog")
  ChiselStage.emitSystemVerilogFile(new TOP(), Array("--target-dir", target))
}
