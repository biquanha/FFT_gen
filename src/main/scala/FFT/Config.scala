package FFT

import org.scalacheck.Prop

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
  val FFTLength = 512
  val useGauss = false
  val supportIFFT = false
  // FFT算法选择，默认为R2DIF
  val fftAlgorithm = FFTAlgorithm.R2DIF
}