package FFT

import org.scalacheck.Prop

trait HasDataConfig {
  val DataWidth = 32
  val BinaryPoint = 30  // 使用30位，避免溢出同时保持高精度
}

// 定义FFT算法类型
object FFTAlgorithm extends Enumeration {
  type FFTAlgorithm = Value
  val R2MDC, R2MDC_Optimized = Value
  // R2MDC: 标准R2MDC架构
  // R2MDC_Optimized: 优化的R2MDC（原错误命名的CooleyTukey）
}

trait HasElaborateConfig {
  val FFTLength = 512
  val useGauss = false
  val supportIFFT = false
  // FFT算法选择，默认为优化版本
  val fftAlgorithm = FFTAlgorithm.R2MDC_Optimized
}