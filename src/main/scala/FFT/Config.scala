package FFT

import org.scalacheck.Prop

trait HasDataConfig {
  val DataWidth = 32
  val BinaryPoint = 16
}

// 定义FFT算法类型
object FFTAlgorithm extends Enumeration {
  type FFTAlgorithm = Value
  val R2MDC, CooleyTukey = Value
}

trait HasElaborateConfig {
  val FFTLength = 512
  val useGauss = false
  val supportIFFT = false
  // 新增：FFT算法选择，默认为R2MDC保证向后兼容
  val fftAlgorithm = FFTAlgorithm.R2MDC
}