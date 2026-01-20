package FFT

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

import java.io.PrintWriter
import scala.math._

// 基于 chiseltest 的 TEST4，用于替代旧 iotesters 版本
class FFTTest4Spec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "TOP TEST4 (Chisel 6 + chiseltest)"

  it should "export data and complete without overflow" in {
    test(new FFTTop).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      // 放宽/关闭 chiseltest 的默认超时限制
      c.clock.setTimeout(0)
      // 固定配置（与 Config.scala 中一致）
      val FFTLength = sys.props.getOrElse("FFT_LEN", "512").toInt
      val BinaryPoint = 30
      val INPUT_ADDR = 0x0000
      val OUTPUT_ADDR = 0x1000

      // 工具类与参考FFT实现
      class Complex(val re: Double, val im: Double) {
        def +(rhs: Complex): Complex = new Complex(re + rhs.re, im + rhs.im)
        def -(rhs: Complex): Complex = new Complex(re - rhs.re, im - rhs.im)
        def *(rhs: Complex): Complex = new Complex(re * rhs.re - im * rhs.im, rhs.re * im + re * rhs.im)
      }
      def fft(x: Array[Complex]): Array[Complex] = {
        require(x.length > 0 && (x.length & (x.length - 1)) == 0)
        def rec(arr: Array[Complex], start: Int, n: Int, stride: Int): Array[Complex] = {
          if (n == 1) return Array(arr(start))
          val X = rec(arr, start, n/2, 2*stride) ++ rec(arr, start + stride, n/2, 2*stride)
          var k = 0
          while (k < n/2) {
            val t = X(k)
            val ang = -2 * math.Pi * k / n
            val cplx = new Complex(math.cos(ang), math.sin(ang)) * X(k + n/2)
            X(k) = t + cplx
            X(k + n/2) = t - cplx
            k += 1
          }
          X
        }
        rec(x, 0, x.length, 1)
      }

      // 文件输出
      val inputDataFile = new PrintWriter("fft_input_data.txt")
      val outputDataFile = new PrintWriter("fft_output_data.txt")
      val configFile = new PrintWriter("fft_config.txt")
      val summaryFile = new PrintWriter("fft_test_summary.txt")

      // 写入配置
      configFile.println(s"FFT_LENGTH=${FFTLength}")
      configFile.println(s"BINARY_POINT=${BinaryPoint}")
      configFile.println(s"ITER_NUM=5")
      configFile.println(s"INPUT_ADDR=0x${INPUT_ADDR.toHexString.toUpperCase}")
      configFile.println(s"OUTPUT_ADDR=0x${OUTPUT_ADDR.toHexString.toUpperCase}")
      configFile.println(s"RANDOM_SEED=12345")
      configFile.println(s"SUPPORT_IFFT=false")
      configFile.flush()

      inputDataFile.println("# FFT输入数据")
      inputDataFile.println("# 格式: iteration_index re_int im_int re_float im_float")
      outputDataFile.println("# FFT输出数据")
      outputDataFile.println("# 格式: iteration_index hw_re_int hw_im_int hw_re_float hw_im_float ref_re_float ref_im_float")

      // chiseltest 帮助函数
      def axiInit(): Unit = {
        c.io.axi.awvalid.poke(false.B)
        c.io.axi.wvalid.poke(false.B)
        c.io.axi.bready.poke(false.B)
        c.io.axi.arvalid.poke(false.B)
        c.io.axi.rready.poke(false.B)
        c.clock.step(5)
      }
      def waitUntil(cond: => Boolean, maxCycles: Int = 200000): Unit = {
        var cycles = 0
        while (!cond && cycles < maxCycles) { c.clock.step(1); cycles += 1 }
        assert(cycles < maxCycles, s"Timeout waiting for condition after ${maxCycles} cycles")
      }
      def axiWrite(data: BigInt): Unit = {
        c.io.axi.awaddr.poke(INPUT_ADDR.U)
        c.io.axi.awvalid.poke(true.B)
        c.io.axi.bready.poke(true.B)
        waitUntil(c.io.axi.awready.peekBoolean())
        c.clock.step(1)
        c.io.axi.awvalid.poke(false.B)

        c.io.axi.wdata.poke(data.U)
        c.io.axi.wstrb.poke("hFF".U)
        c.io.axi.wvalid.poke(true.B)
        waitUntil(c.io.axi.wready.peekBoolean())
        c.clock.step(1)
        c.io.axi.wvalid.poke(false.B)

        waitUntil(c.io.axi.bvalid.peekBoolean())
        c.clock.step(1)
        c.io.axi.bready.poke(false.B)
        c.clock.step(1)
      }
      def axiRead(): BigInt = {
        c.io.axi.araddr.poke(OUTPUT_ADDR.U)
        c.io.axi.arvalid.poke(true.B)
        c.io.axi.rready.poke(true.B)
        waitUntil(c.io.axi.arready.peekBoolean())
        c.clock.step(1)
        c.io.axi.arvalid.poke(false.B)
        waitUntil(c.io.axi.rvalid.peekBoolean())
        val data = c.io.axi.rdata.peek().litValue
        c.clock.step(1)
        c.io.axi.rready.poke(false.B)
        c.clock.step(1)
        data
      }

      // 开始测试
      axiInit()
      val bound = math.pow(2.0, BinaryPoint)
      val iterNum = 5
      summaryFile.println(s"FFT测试配置: FFT_LENGTH=${FFTLength}, BINARY_POINT=${BinaryPoint}, ITER_NUM=${iterNum}")
      summaryFile.println(s"输入信号类型: 正弦波 (周期256点, 虚部恒为0)")
      summaryFile.println(s"测试开始时间: ${java.time.LocalDateTime.now()}")

      var errorAcc = 0.0
      var totalOv = 0

      for (t <- 0 until iterNum) {
        val a = Array.ofDim[Complex](FFTLength)
        inputDataFile.println(s"# === Iteration ${t+1} ===")
        outputDataFile.println(s"# === Iteration ${t+1} ===")

        // 输入：正弦波
        val amplitude = bound / 1024.0
        for (i <- 0 until FFTLength) {
          val angle = 2.0 * math.Pi * i / 256.0
          val re = (amplitude * math.sin(angle)).toInt
          val im = 0
          a(i) = new Complex(2.0 * re / bound, 0.0)
          inputDataFile.println(s"${t}_${i} ${re} ${im} ${a(i).re} ${a(i).im}")

          val reUInt = if (re < 0) (re + (1L << 32)) else re.toLong
          val imUInt = if (im < 0) (im + (1L << 32)) else im.toLong
          val data64 = (BigInt(imUInt) << 32) | (BigInt(reUInt) & BigInt("FFFFFFFF", 16))
          axiWrite(data64)
        }

        // 处理等待（与原逻辑一致）
        // 保守等待更多周期，覆盖不同实现的流水/存储延迟
        val totalProcessingCycles = FFTLength * 4
        c.clock.step(totalProcessingCycles + 50)

        // 在开始读之前，等待 DUT 进入 OUTPUT 阶段（arready 变为 true）
        waitUntil(c.io.axi.arready.peekBoolean())

        // 计算参考与读取输出
        val ref = fft(a)
        var errorOne = 0.0
        var ovNum1 = 0
        val eps = 1e-9
        for (i <- 0 until FFTLength) {
          val ref1 = ref(i)
          val data64 = axiRead()
          val reRaw = data64 & ((BigInt(1) << 32) - 1)
          val imRaw = (data64 >> 32) & ((BigInt(1) << 32) - 1)
          val reSigned = if (reRaw >= (BigInt(1) << 31)) reRaw - (BigInt(1) << 32) else reRaw
          val imSigned = if (imRaw >= (BigInt(1) << 31)) imRaw - (BigInt(1) << 32) else imRaw
          val hwRe = 2.0 * reSigned.toDouble / bound
          val hwIm = 2.0 * imSigned.toDouble / bound

          outputDataFile.println(s"${t}_${i} ${reSigned} ${imSigned} ${hwRe} ${hwIm} ${ref1.re} ${ref1.im}")

          val absErrorRe = math.abs(hwRe - ref1.re)
          val absErrorIm = math.abs(hwIm - ref1.im)
          val absError = math.hypot(absErrorRe, absErrorIm)
          val refMag = math.hypot(ref1.re, ref1.im)
          val e = if (refMag > 0.01) absError / (refMag + eps) else { if (absError < 0.0001) 0.0 else absError / 0.01 }
          if (e <= 0.5) errorOne += e else ovNum1 += 1
        }

        errorOne = if ((FFTLength - ovNum1) > 0) errorOne / (FFTLength - ovNum1) else 0.0
        totalOv += ovNum1
        errorAcc += errorOne
        val errorOnePercent = errorOne * 100
        summaryFile.println(s"Iteration ${t+1}: Error=${errorOnePercent}%, Overflow=${ovNum1}")
      }

      val finalError = (errorAcc / iterNum) * 100
      val finalSummary = s"测试完成: 平均错误率=${finalError}%, 总溢出=${totalOv}"
      summaryFile.println(s"\n${finalSummary}")
      summaryFile.println(s"测试结束时间: ${java.time.LocalDateTime.now()}")

      inputDataFile.close()
      outputDataFile.close()
      configFile.close()
      summaryFile.close()

      // 软性检查：无溢出，误差极小
      assert(totalOv == 0)
      assert(finalError < 1e-5)
    }
  }
}

// 兼容 runMain 的入口：保持 README 用法
object FFTTestMain4 extends App {
  org.scalatest.tools.Runner.run(Array("-o", "-s", "FFT.FFTTest4Spec"))
}
