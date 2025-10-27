package FFT

trait HasDataConfig {
  val DataWidth = 32
  val BinaryPoint = 30  // 使用30位，避免溢出同时保持高精度
}

// 定义FFT算法类型
object FFTAlgorithm extends Enumeration {
  type FFTAlgorithm = Value
  val R2MDC, R2MDC_Optimized, R2CSS, R2DIF = Value
  // R2MDC: 标准R2MDC架构 (Radix-2 Multi-path Delay Commutator, DIT)
  // R2MDC_Optimized: 优化的R2MDC（原错误命名的CooleyTukey）
  // R2CSS: Radix-2 Cascaded Single-path with Feedback架构
  // R2DIF: Radix-2 Decimation-In-Frequency架构
}

trait HasElaborateConfig {
  // 允许通过系统属性覆盖：-DFFT_LEN=512 -DFFT_ALGO=R2DIF|R2MDC|R2MDC_Optimized|R2CSS -DSUPPORT_IFFT=true
  private def prop(name: String, default: String) = scala.sys.props.getOrElse(name, default)

  val FFTLength: Int = prop("FFT_LEN", "512").toInt
  val useGauss: Boolean = prop("USE_GAUSS", "false").toBoolean
  val supportIFFT: Boolean = prop("SUPPORT_IFFT", "false").toBoolean

  val fftAlgorithm: FFTAlgorithm.Value = prop("FFT_ALGO", "R2DIF").toUpperCase match {
    case "R2MDC"            => FFTAlgorithm.R2MDC
    case "R2MDC_OPTIMIZED"  => FFTAlgorithm.R2MDC_Optimized
    case "R2CSS"            => FFTAlgorithm.R2CSS
    case "R2DIF"            => FFTAlgorithm.R2DIF
    case other              =>
      println(s"[WARN] Unknown FFT_ALGO=$other, fallback to R2DIF")
      FFTAlgorithm.R2DIF
  }
}
