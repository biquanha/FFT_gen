

package FFT

import chisel3.iotesters
import chisel3.iotesters.{ChiselFlatSpec, Driver, PeekPokeTester}
import chisel3.util._

class Complex(val re: Double, val im: Double) {
  def +(rhs: Complex): Complex = new Complex(re + rhs.re, im + rhs.im)
  def -(rhs: Complex): Complex = new Complex(re - rhs.re, im - rhs.im)
  def *(rhs: Complex): Complex = new Complex(re * rhs.re - im * rhs.im, rhs.re * im + re * rhs.im)
  //def r = re

  def magnitude: Double = Math.hypot(re, im)
  def phase: Double = Math.atan2(im, re)

  override def toString: String = s"Complex($re, $im)"
}

// only support FFT
class FFTTest(c:FFT) extends PeekPokeTester(c)
  with HasDataConfig
  with HasElaborateConfig {
  require(!supportIFFT)
  def fft(x: Array[Complex]): Array[Complex] = {
    require(x.length > 0 && (x.length & (x.length - 1)) == 0, "array size should be power of two")
    fft(x, 0, x.length, 1)
  }

  def fft(x: Array[Double]): Array[Complex] = fft(x.map(re => new Complex(re, 0.0)))
  def rfft(x: Array[Double]): Array[Complex] = fft(x).take(x.length / 2 + 1)

  private def fft(x: Array[Complex], start: Int, n: Int, stride: Int) : Array[Complex] = {
    if (n == 1) {
      return Array(x(start))
    }

    val X = fft(x, start, n / 2, 2 * stride) ++ fft(x, start + stride, n / 2, 2 * stride)

    for (k <- 0 until n / 2) {
      val t = X(k)
      val arg = -2 * math.Pi * k / n
      val c = new Complex(math.cos(arg), math.sin(arg)) * X(k + n / 2)
      X(k) = t + c
      X(k + n / 2) = t - c
    }
    X
  }

  def range(a: Int, upBound: Int, downBound: Int) : Int = {
    assert(upBound < 32)
    assert(downBound >= 0)
    return (a >> downBound) & (0xffffffff >>> (31 - upBound + downBound))
  }

  def reverse(a: Int, len: Int): Int = {
    var res: Int = 0
    for(i <- 0 until len) {
      res = res | range(a, i, i) << (len-1-i)
    }
    res
  }

  val r = new scala.util.Random
  var bound: Double = math.pow(2.0, BinaryPoint)
  var error: Double = 0
  var ovNum: Int = 0
  var iterNum: Int = 10

  for (t <- 0 until iterNum) {
    var a = new Array[Complex](FFTLength)
    var cnt = 0
    for (i <- 0 until FFTLength) {
      var re = -bound.toInt / 2 + r.nextInt(bound.toInt)
      var im = -bound.toInt / 2 + r.nextInt(bound.toInt)
      a(cnt) = new Complex(2 * re / bound, 2 * im / bound)
      poke(c.io.dIn.re, re)
      poke(c.io.dIn.im, im)
      if (i == 0) {
        poke(c.io.din_valid, 1)
      } else {
        poke(c.io.din_valid, 0)
      }
      step(1)
      cnt += 1
    }
    var ref = fft(a)

    var errorOne: Double = 0
    var error1: Double = 0
    var ovNum1: Int = 0
    var eps: Double = 1e-9
    for (i <- 0 until FFTLength / 2) {
      var ref1 = ref(reverse(i * 2, log2Ceil(FFTLength)))
      var d1 = peek(c.io.dOut1)
      error1 = math.abs((((2 * d1("re").toDouble / bound) - ref1.re) / (ref1.re + eps) + ((2 * d1("im").toDouble / bound) - ref1.im) / (ref1.im + eps)) / 2.0)
      if (error1 <= 0.5) {
        errorOne += error1
      } else {
        ovNum1 += 1
      }
      var ref2 = ref(reverse(i * 2 + 1, log2Ceil(FFTLength)))
      var d2 = peek(c.io.dOut2)
      error1 = math.abs((((2 * d2("re").toDouble / bound) - ref2.re) / (ref2.re + eps) + ((2 * d2("im").toDouble / bound) - ref2.im) / (ref2.im + eps)) / 2.0)
      if (error1 <= 0.5) {
        errorOne += error1
      } else {
        ovNum1 += 1
      }
      step(1)
    }
    errorOne = errorOne / (FFTLength - ovNum1)
    ovNum += ovNum1
    error += errorOne
    var errorOnePercent = errorOne*100
    printf("In this sample, Error rate: %.2f%% | number of ovs: %d\n", errorOnePercent, ovNum1)
  }
  error /= iterNum
  print("Total error rate is: " + error*100 + "%\n")
  print(ovNum + " of " + iterNum * FFTLength + " overflowed! " + "The overlow ratio is " + 100 * ovNum / (FFTLength * iterNum).toDouble  + "%" + "\n")
}

// support FFT / IFFT
class FFTTest2(c:FFT) extends PeekPokeTester(c)
  with HasDataConfig
  with HasElaborateConfig {
  require(supportIFFT)
  def fft(x: Array[Complex]): Array[Complex] = {
    require(x.length > 0 && (x.length & (x.length - 1)) == 0, "array size should be power of two")
    fft(x, 0, x.length, 1)
  }

  def fft(x: Array[Double]): Array[Complex] = fft(x.map(re => new Complex(re, 0.0)))
  def rfft(x: Array[Double]): Array[Complex] = fft(x).take(x.length / 2 + 1)

  private def fft(x: Array[Complex], start: Int, n: Int, stride: Int) : Array[Complex] = {
    if (n == 1) {
      return Array(x(start))
    }

    val X = fft(x, start, n / 2, 2 * stride) ++ fft(x, start + stride, n / 2, 2 * stride)

    for (k <- 0 until n / 2) {
      val t = X(k)
      val arg = -2 * math.Pi * k / n
      val c = new Complex(math.cos(arg), math.sin(arg)) * X(k + n / 2)
      X(k) = t + c
      X(k + n / 2) = t - c
    }
    X
  }

  def ifft(x: Array[Complex]): Array[Complex] = {
    val n = x.length
    val res = new Array[Complex](n)
    for (i <- 0 until n) {
      res(i) = new Complex(0, 0)
    }
    for (i <- 0 until n) {
      for (j <- 0 until n) {
        val arg = 2 * math.Pi * j * i / n
        res(i) += new Complex(math.cos(arg), math.sin(arg)) * x(j)
      }
      res(i) = new Complex(1.0 / n, 0) * res(i)
    }
    res
  }

  def range(a: Int, upBound: Int, downBound: Int) : Int = {
    assert(upBound < 32)
    assert(downBound >= 0)
    return (a >> downBound) & (0xffffffff >>> (31 - upBound + downBound))
  }

  def reverse(a: Int, len: Int): Int = {
    var res: Int = 0
    for(i <- 0 until len) {
      res = res | range(a, i, i) << (len-1-i)
    }
    res
  }

  val r = new scala.util.Random
  var bound: Double = math.pow(2.0, BinaryPoint)
  var error: Double = 0
  var ovNum: Int = 0
  var iterNum: Int = 100

  for (t <- 0 until iterNum) {
    var a = new Array[Complex](FFTLength)
    var cnt = 0
    for (i <- 0 until FFTLength) {
      var re = -bound.toInt / 2 + r.nextInt(bound.toInt)
      var im = -bound.toInt / 2 + r.nextInt(bound.toInt)
      a(cnt) = new Complex(2 * re / bound, 2 * im / bound)
      poke(c.io.dIn.re, re)
      poke(c.io.dIn.im, im)
      poke(c.io.din_valid, 1)
      poke(c.io.mode.get, 0)
      step(1)
      cnt += 1
    }
    var ref = fft(a)

    var errorOne: Double = 0
    var error1: Double = 0
    var ovNum1: Int = 0
    var eps: Double = 1e-9
    for (i <- 0 until FFTLength / 2) {
      var ref1 = ref(reverse(i * 2, log2Ceil(FFTLength)))
      var d1 = peek(c.io.dOut1)
      error1 = math.abs((((2 * d1("re").toDouble / bound) - ref1.re) / (ref1.re + eps) + ((2 * d1("im").toDouble / bound) - ref1.im) / (ref1.im + eps)) / 2.0)
      if (error1 <= 0.5) {
        errorOne += error1
      } else {
        ovNum1 += 1
      }
      var ref2 = ref(reverse(i * 2 + 1, log2Ceil(FFTLength)))
      var d2 = peek(c.io.dOut2)
      error1 = math.abs((((2 * d2("re").toDouble / bound) - ref2.re) / (ref2.re + eps) + ((2 * d2("im").toDouble / bound) - ref2.im) / (ref2.im + eps)) / 2.0)
      if (error1 <= 0.5) {
        errorOne += error1
      } else {
        ovNum1 += 1
      }
      step(1)
    }
    errorOne = errorOne / (FFTLength - ovNum1)
    ovNum += ovNum1
    error += errorOne
    var errorOnePercent = errorOne*100
    printf("In this FFT sample, Error rate: %.2f%% | number of ovs: %d\n", errorOnePercent, ovNum1)
  }
  error /= iterNum
  print("Total error rate in FFT is: " + error*100 + "%\n")
  print(ovNum + " of " + iterNum * FFTLength + " overflowed! " + "The overlow ratio is " + 100 * ovNum / (FFTLength * iterNum).toDouble  + "%" + "\n")


  error = 0
  ovNum = 0
  for (t <- 0 until iterNum) {
    var a = new Array[Complex](FFTLength)
    var cnt = 0
    for (i <- 0 until FFTLength) {
      var re = -bound.toInt / 2 + r.nextInt(bound.toInt)
      var im = -bound.toInt / 2 + r.nextInt(bound.toInt)
      a(cnt) = new Complex(2 * re / bound, 2 * im / bound)
      poke(c.io.dIn.re, re)
      poke(c.io.dIn.im, im)
      poke(c.io.din_valid, 1)
      poke(c.io.mode.get, 1)
      step(1)
      cnt += 1
    }
    var ref = ifft(a)

    var errorOne: Double = 0
    var error1: Double = 0
    var ovNum1: Int = 0
    var eps: Double = 1e-9
    for (i <- 0 until FFTLength / 2) {
      var ref1 = ref(reverse(i * 2, log2Ceil(FFTLength)))
      var d1 = peek(c.io.dOut1)
      error1 = math.abs((((2 * d1("re").toDouble / bound) - ref1.re) / (ref1.re + eps) + ((2 * d1("im").toDouble / bound) - ref1.im) / (ref1.im + eps)) / 2.0)
      if (error1 <= 0.5) {
        errorOne += error1
      } else {
        ovNum1 += 1
      }
      var ref2 = ref(reverse(i * 2 + 1, log2Ceil(FFTLength)))
      var d2 = peek(c.io.dOut2)
      error1 = math.abs((((2 * d2("re").toDouble / bound) - ref2.re) / (ref2.re + eps) + ((2 * d2("im").toDouble / bound) - ref2.im) / (ref2.im + eps)) / 2.0)
      if (error1 <= 0.5) {
        errorOne += error1
      } else {
        ovNum1 += 1
      }
      step(1)
    }
    errorOne = errorOne / (FFTLength - ovNum1)
    ovNum += ovNum1
    error += errorOne
    var errorOnePercent = errorOne*100
    printf("In this IFFT sample, Error rate: %.2f%% | number of ovs: %d\n", errorOnePercent, ovNum1)
  }
  error /= iterNum
  print("Total error rate in IFFT is: " + error*100 + "%\n")
  print(ovNum + " of " + iterNum * FFTLength + " overflowed! " + "The overlow ratio is " + 100 * ovNum / (FFTLength * iterNum).toDouble  + "%" + "\n")
}

// only support FFTtop
class FFTTest3(c:FFTReorder) extends PeekPokeTester(c)
  with HasDataConfig
  with HasElaborateConfig {
  require(!supportIFFT)
  def fft(x: Array[Complex]): Array[Complex] = {
    require(x.length > 0 && (x.length & (x.length - 1)) == 0, "array size should be power of two")
    fft(x, 0, x.length, 1)
  }

  def fft(x: Array[Double]): Array[Complex] = fft(x.map(re => new Complex(re, 0.0)))
  def rfft(x: Array[Double]): Array[Complex] = fft(x).take(x.length / 2 + 1)

  private def fft(x: Array[Complex], start: Int, n: Int, stride: Int) : Array[Complex] = {
    if (n == 1) {
      return Array(x(start))
    }

    val X = fft(x, start, n / 2, 2 * stride) ++ fft(x, start + stride, n / 2, 2 * stride)

    for (k <- 0 until n / 2) {
      val t = X(k)
      val arg = -2 * math.Pi * k / n
      val c = new Complex(math.cos(arg), math.sin(arg)) * X(k + n / 2)
      X(k) = t + c
      X(k + n / 2) = t - c
    }
    X
  }

  def range(a: Int, upBound: Int, downBound: Int) : Int = {
    assert(upBound < 32)
    assert(downBound >= 0)
    return (a >> downBound) & (0xffffffff >>> (31 - upBound + downBound))
  }

  def reverse(a: Int, len: Int): Int = {
    var res: Int = 0
    for(i <- 0 until len) {
      res = res | range(a, i, i) << (len-1-i)
    }
    res
  }

  val r = new scala.util.Random
  var bound: Double = math.pow(2.0, BinaryPoint)
  var error: Double = 0
  var ovNum: Int = 0
  var iterNum: Int = 10

  for (t <- 0 until iterNum) {
    var a = new Array[Complex](FFTLength)
    var cnt = 0
    for (i <- 0 until FFTLength) {
      var re = -bound.toInt / 2 + r.nextInt(bound.toInt)
      var im = -bound.toInt / 2 + r.nextInt(bound.toInt)
      a(cnt) = new Complex(2 * re / bound, 2 * im / bound)
      poke(c.io.dIn.re, re)
      poke(c.io.dIn.im, im)
      if (i == 0) {
        poke(c.io.din_valid, 1)
      } else {
        poke(c.io.din_valid, 0)
      }
      step(1)
      cnt += 1
    }
    var ref = fft(a)

    var errorOne: Double = 0
    var error1: Double = 0
    var ovNum1: Int = 0
    var eps: Double = 1e-9
    step(FFTLength / 2)
    for (i <- 0 until FFTLength) {
      var ref1 = ref(i)
      var d1 = peek(c.io.dOut)
      error1 = math.abs((((2 * d1("re").toDouble / bound) - ref1.re) / (ref1.re + eps) + ((2 * d1("im").toDouble / bound) - ref1.im) / (ref1.im + eps)) / 2.0)
      if (error1 <= 0.5) {
        errorOne += error1
      } else {
        ovNum1 += 1
      }
      step(1)
    }
    errorOne = errorOne / (FFTLength - ovNum1)
    ovNum += ovNum1
    error += errorOne
    var errorOnePercent = errorOne * 100
    printf("In this sample, Error rate: %.2f%% | number of ovs: %d\n", errorOnePercent, ovNum1)
  }
}

// FFT测试 - 添加数据导出功能
// 在原有FFTTest4类基础上添加数据保存功能
class FFTTest4(c: TOP) extends PeekPokeTester(c) 
  with HasDataConfig with HasElaborateConfig {
  
  require(!supportIFFT)
  
  // 固定地址
  val INPUT_ADDR = 0x0000
  val OUTPUT_ADDR = 0x1000
  
  // === 数据导出功能 - 新增部分 ===
  import java.io.PrintWriter
  
  // 创建数据导出文件
  val inputDataFile = new PrintWriter("fft_input_data.txt")
  val outputDataFile = new PrintWriter("fft_output_data.txt") 
  val configFile = new PrintWriter("fft_config.txt")
  val summaryFile = new PrintWriter("fft_test_summary.txt")
  
  // 写入配置信息
  println("导出测试配置...")
  configFile.println(s"FFT_LENGTH=${FFTLength}")
  configFile.println(s"BINARY_POINT=${BinaryPoint}")
  configFile.println(s"ITER_NUM=5")
  configFile.println(s"INPUT_ADDR=0x${INPUT_ADDR.toHexString.toUpperCase}")
  configFile.println(s"OUTPUT_ADDR=0x${OUTPUT_ADDR.toHexString.toUpperCase}")
  configFile.println(s"RANDOM_SEED=12345")
  configFile.println(s"SUPPORT_IFFT=${supportIFFT}")
  configFile.flush()
  
  // 写入文件头注释
  inputDataFile.println("# FFT输入数据")
  inputDataFile.println("# 格式: iteration_index re_int im_int re_float im_float")
  inputDataFile.println("# re_int/im_int: 定点数整数表示")
  inputDataFile.println("# re_float/im_float: 归一化浮点数表示 [-1, 1)")
  inputDataFile.flush()
  
  outputDataFile.println("# FFT输出数据") 
  outputDataFile.println("# 格式: iteration_index hw_re_int hw_im_int hw_re_float hw_im_float ref_re_float ref_im_float")
  outputDataFile.println("# hw_*: 硬件输出结果")
  outputDataFile.println("# ref_*: 软件参考结果")
  outputDataFile.flush()
  
  // === 保持原有的FFT参考实现 ===
  def fft(x: Array[Complex]): Array[Complex] = {
    require(x.length > 0 && (x.length & (x.length - 1)) == 0, "array size should be power of two")
    fft(x, 0, x.length, 1)
  }
  
  def fft(x: Array[Double]): Array[Complex] = fft(x.map(re => new Complex(re, 0.0)))

  def rfft(x: Array[Double]): Array[Complex] = fft(x).take(x.length / 2 + 1)
  
  private def fft(x: Array[Complex], start: Int, n: Int, stride: Int): Array[Complex] = {
    if (n == 1) {
      return Array(x(start))
    }
    val X = fft(x, start, n / 2, 2 * stride) ++ fft(x, start + stride, n / 2, 2 * stride)
    for (k <- 0 until n / 2) {
      val t = X(k)
      val arg = -2 * math.Pi * k / n
      val c = new Complex(math.cos(arg), math.sin(arg)) * X(k + n / 2)
      X(k) = t + c
      X(k + n / 2) = t - c
    }
    X
  }
  
  def range(a: Int, upBound: Int, downBound: Int): Int = {
    assert(upBound < 32)
    assert(downBound >= 0)
    return (a >> downBound) & (0xffffffff >>> (31 - upBound + downBound))
  }
  
  def reverse(a: Int, len: Int): Int = {
    var res: Int = 0
    for(i <- 0 until len) {
      res = res | range(a, i, i) << (len-1-i)
    }
    res
  }
  
  // === 保持原有的AXI操作 ===
  def axiWrite(data: Long): Unit = {
    poke(c.io.axi.awaddr, INPUT_ADDR)
    poke(c.io.axi.awvalid, 1)
    poke(c.io.axi.bready, 1)
    
    while (peek(c.io.axi.awready) == 0) {
      step(1)
    }
    step(1)
    poke(c.io.axi.awvalid, 0)
    
    poke(c.io.axi.wdata, data)
    poke(c.io.axi.wvalid, 1)
    poke(c.io.axi.wstrb, 0xFF)
    
    while (peek(c.io.axi.wready) == 0) {
      step(1)
    }
    step(1)
    poke(c.io.axi.wvalid, 0)
    
    while (peek(c.io.axi.bvalid) == 0) {
      step(1)
    }
    step(1)
    poke(c.io.axi.bready, 0)
    step(1)
  }
  
  def axiRead(): BigInt = {
    poke(c.io.axi.araddr, OUTPUT_ADDR)
    poke(c.io.axi.arvalid, 1)
    poke(c.io.axi.rready, 1)
    
    while (peek(c.io.axi.arready) == 0) {
      step(1)
    }
    step(1)
    poke(c.io.axi.arvalid, 0)
    
    while (peek(c.io.axi.rvalid) == 0) {
      step(1)
    }
    
    val data = peek(c.io.axi.rdata)
    step(1)
    poke(c.io.axi.rready, 0)
    step(1)
    
    data
  }
  
  // 初始化AXI接口
  poke(c.io.axi.awvalid, 0)
  poke(c.io.axi.wvalid, 0)
  poke(c.io.axi.bready, 0)
  poke(c.io.axi.arvalid, 0)
  poke(c.io.axi.rready, 0)
  step(5)
  
  // === 主测试逻辑 - 添加数据导出 ===
  
  // 使用固定种子确保可重现性
  val r = new scala.util.Random(12345)
  var bound: Double = math.pow(2.0, BinaryPoint)
  var error: Double = 0
  var ovNum: Int = 0
  var iterNum: Int = 5
  
  println(s"开始FFT测试 - 正弦波输入模式")
  println(s"FFT长度: ${FFTLength}, 二进制点: ${BinaryPoint}, 迭代次数: ${iterNum}")
  println(s"输入信号: 正弦波, 周期=256点, 虚部=0")
  summaryFile.println(s"FFT测试配置: FFT_LENGTH=${FFTLength}, BINARY_POINT=${BinaryPoint}, ITER_NUM=${iterNum}")
  summaryFile.println(s"输入信号类型: 正弦波 (周期256点, 虚部恒为0)")
  summaryFile.println(s"测试开始时间: ${java.time.LocalDateTime.now()}")
  
  for (t <- 0 until iterNum) {
    var a = new Array[Complex](FFTLength)
    var cnt = 0
    
    println(s"第${t+1}次迭代开始...")
    println(s"  生成正弦波: 频率=${FFTLength/256.0}Hz (在${FFTLength}点FFT中)")
    
    // 在数据文件中标记迭代开始
    inputDataFile.println(s"# === Iteration ${t+1} ===")
    outputDataFile.println(s"# === Iteration ${t+1} ===")
    
    // 阶段1: 生成和输入数据 - 使用正弦波
    // 生成正弦波数据，虚部为0
    // 对于512点FFT，能量会集中，需要更小的输入避免溢出
    // BinaryPoint=31时，使用Q1.31格式，范围是[-1, 1)
    // 为避免溢出，使用较小的幅度
    // 修改：从bound/256改为bound/1024，避免FFT输出溢出
    // FFT增益约为N/2=256，所以输入幅度应小于满量程的1/256
    val amplitude = bound / 1024.0  // 避免FFT输出溢出
    var minVal = Int.MaxValue
    var maxVal = Int.MinValue
    
    for (i <- 0 until FFTLength) {
      // 计算正弦波值 - 每个周期256个点
      val angle = 2.0 * math.Pi * i / 256.0
      val sin_value = amplitude * math.sin(angle)
      
      // 转换为定点数整数
      var re = sin_value.toInt
      var im = 0  // 虚部始终为0
      
      // 归一化到[-1, 1]范围存储到Complex数组
      a(cnt) = new Complex(2.0 * re / bound, 0.0)
      
      // 记录最大最小值
      if (re < minVal) minVal = re
      if (re > maxVal) maxVal = re
      
      // === 导出输入数据 ===
      inputDataFile.println(s"${t}_${i} ${re} ${im} ${a(cnt).re} ${a(cnt).im}")
      
      // 处理有符号数
      val reUInt = if (re < 0) (re + (1L << 32)) else re.toLong
      val imUInt = if (im < 0) (im + (1L << 32)) else im.toLong
      val data64 = (imUInt << 32) | (reUInt & 0xFFFFFFFFL)
      
      if (i < 5 || i >= FFTLength - 3 || i == 64 || i == 128 || i == 192 || i == 256 || i == 320 || i == 384 || i == 448) {
        println(f"  输入[${i}%3d]: re=${re}%6d, im=${im}%6d -> 归一化后: (${a(cnt).re}%.6f, ${a(cnt).im}%.6f)")
      }
      
      axiWrite(data64)
      cnt += 1
    }
    
    println(s"  正弦波数值范围: [${minVal}, ${maxVal}]")
    println(s"  正弦波周期: 256点, 频率bin: ${FFTLength/256}")
    
    inputDataFile.flush()
    
    // 阶段2: 等待计算
    val totalProcessingCycles = FFTLength + FFTLength/2 + 1
    step(totalProcessingCycles + 10)
    
    // 计算参考FFT结果
    var ref = fft(a)
    var errorOne: Double = 0
    var error1: Double = 0
    var ovNum1: Int = 0
    var eps: Double = 1e-9
    
    // 阶段3: 读取输出数据
    for (i <- 0 until FFTLength) {
      var ref1 = ref(i)
      
      val data64 = axiRead()
      val reRaw = data64 & 0xFFFFFFFFL
      val imRaw = (data64 >> 32) & 0xFFFFFFFFL
      
      val reSigned = if (reRaw >= (1L << 31)) (reRaw - (1L << 32)) else reRaw
      val imSigned = if (imRaw >= (1L << 31)) (imRaw - (1L << 32)) else imRaw
      
      // 转换为浮点数
      val reFloat = 2.0 * reSigned.toDouble / bound
      val imFloat = 2.0 * imSigned.toDouble / bound
      
      // === 导出输出数据 ===
      outputDataFile.println(s"${t}_${i} ${reSigned} ${imSigned} ${reFloat} ${imFloat} ${ref1.re} ${ref1.im}")
      
      if (i < 3 || i >= FFTLength - 3) {
        println(s"  输出[${i}]: hw=(${reFloat}, ${imFloat}), ref=(${ref1.re}, ${ref1.im})")
      }
      
      // 改进的误差计算：混合使用绝对误差和相对误差
      val hwRe = 2 * reSigned.toDouble / bound
      val hwIm = 2 * imSigned.toDouble / bound

      // 计算绝对误差
      val absErrorRe = math.abs(hwRe - ref1.re)
      val absErrorIm = math.abs(hwIm - ref1.im)
      val absError = math.sqrt(absErrorRe * absErrorRe + absErrorIm * absErrorIm)

      // 计算参考值幅度
      val refMag = math.sqrt(ref1.re * ref1.re + ref1.im * ref1.im)

      // 对于大幅度bin（>0.01）使用相对误差，对于小幅度bin使用绝对误差阈值
      if (refMag > 0.01) {
        // 大幅度bin：使用相对误差
        val relError = absError / (refMag + eps)
        error1 = relError
      } else {
        // 小幅度bin：使用绝对误差，归一化到[0,1]范围以便统计
        // 如果绝对误差<0.0001则认为正确，否则按绝对误差大小计算
        error1 = if (absError < 0.0001) 0.0 else absError / 0.01
      }

      if (error1 <= 0.5) {
        errorOne += error1
      } else {
        ovNum1 += 1
      }
      
      step(1)
    }
    
    outputDataFile.flush()
    
    errorOne = if((FFTLength - ovNum1) > 0) errorOne / (FFTLength - ovNum1) else 0
    ovNum += ovNum1
    error += errorOne
    var errorOnePercent = errorOne * 100
    
    println(s"第${t+1}次迭代完成:")
    println(s"  错误率: ${errorOnePercent}%")
    println(s"  溢出数量: ${ovNum1}")
    
    // 保存迭代结果到总结文件
    summaryFile.println(s"Iteration ${t+1}: Error=${errorOnePercent}%, Overflow=${ovNum1}")
    summaryFile.flush()
  }
  
  // === 完成测试，关闭文件 ===
  
  val finalError = (error / iterNum) * 100
  val finalSummary = s"测试完成: 平均错误率=${finalError}%, 总溢出=${ovNum}"
  
  println(s"\n${finalSummary}")
  summaryFile.println(s"\n${finalSummary}")
  summaryFile.println(s"测试结束时间: ${java.time.LocalDateTime.now()}")
  
  // 关闭所有文件
  inputDataFile.close()
  outputDataFile.close()
  summaryFile.close()
  
  println("\n=== 数据导出完成 ===")
  println("生成文件:")
  println("  fft_config.txt       - 测试配置参数")
  println("  fft_input_data.txt   - 输入数据 (格式: iter_idx re_int im_int re_float im_float)")
  println("  fft_output_data.txt  - 输出数据 (格式: iter_idx hw_re hw_im hw_re_f hw_im_f ref_re ref_im)")
  println("  fft_test_summary.txt - 测试结果总结")
  println("\n可以开始C程序验证!")
}
object FFTTestMain extends App {
  iotesters.Driver.execute(args, () => new FFT) {
    c => new FFTTest(c)
  }
}
object FFTTestMain2 extends App {
  iotesters.Driver.execute(args, () => new FFT) {
    c => new FFTTest2(c)
  }
}
object FFTTestMain3 extends App {
  iotesters.Driver.execute(args, () => new FFTReorder) {
    c => new FFTTest3(c)
  }
}

object FFTTestMain4 extends App {
  iotesters.Driver.execute(args, () => new TOP) {
    c => new FFTTest4(c)
  }
}
