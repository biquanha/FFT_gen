

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

class FFTTest4(c: TOP) extends PeekPokeTester(c) 
  with HasDataConfig with HasElaborateConfig {
  
  require(!supportIFFT)
  
  // 固定地址
  val INPUT_ADDR = 0x0000
  val OUTPUT_ADDR = 0x1000
  
  // 保持原有的FFT参考实现
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
  
  // AXI写操作
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
  
  // AXI读操作
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
  
  // 严格按照原始测试逻辑进行
  val r = new scala.util.Random
  var bound: Double = math.pow(2.0, BinaryPoint)
  var error: Double = 0
  var ovNum: Int = 0
  var iterNum: Int = 5
  
  for (t <- 0 until iterNum) {
    var a = new Array[Complex](FFTLength)
    var cnt = 0
    
    println("开始第" + (t+1) + "次FFT测试 - 严格按原始逻辑")
    
    // 等价于原有的 poke(c.io.dout.ready, 1) - 在新设计中自动处理
    
    // 第一阶段：输入FFTLength个复数（等价于原始输入循环）
    println("阶段1: 输入" + FFTLength + "个复数...")
    for (i <- 0 until FFTLength) {
      var re = -bound.toInt / 2 + r.nextInt(bound.toInt)
      var im = -bound.toInt / 2 + r.nextInt(bound.toInt)
      a(cnt) = new Complex(2 * re / bound, 2 * im / bound)
      
      // 等价于原有的 poke(c.io.din.bits.re, re) 和 poke(c.io.din.bits.im, im)
      val reUInt = if (re < 0) (re + (1L << 32)) else re.toLong
      val imUInt = if (im < 0) (im + (1L << 32)) else im.toLong
      val data64 = (imUInt << 32) | (reUInt & 0xFFFFFFFFL)
      
      if (i < 3) {
        println("输入[" + i + "]: re=" + re + ", im=" + im)
      }
      
      axiWrite(data64)
      
      // 等价于原有的 if (i == 0) poke(c.io.din.valid, 1) else poke(c.io.din.valid, 0)
      // 和 step(1) - 现在由TOP模块内部在PROCESSING阶段处理
      
      cnt += 1
    }
    
    println("阶段1完成: 已输入所有" + FFTLength + "个复数")
    
    // 第二阶段：等待FFT计算（等价于原始的step(FFTLength / 2 + 1)）
    // 但现在由TOP模块内部自动处理，包括：
    // - FFTLength个周期的逐个输入到FFT
    // - FFTLength/2+1个周期的等待计算完成
    println("阶段2: 等待FFT计算完成...")
    val totalProcessingCycles = FFTLength + FFTLength/2 + 1
    println("预计需要" + totalProcessingCycles + "个周期完成处理")
    
    // 在这个阶段，任何读取尝试都应该失败
    println("验证：在处理阶段尝试读取（应该失败）...")
    poke(c.io.axi.araddr, OUTPUT_ADDR)
    poke(c.io.axi.arvalid, 1)
    step(5)  // 等待几个周期
    if (peek(c.io.axi.arready) == 0) {
      println("✓ 正确：处理阶段不允许读取")
    } else {
      println("✗ 错误：处理阶段意外允许了读取")
    }
    poke(c.io.axi.arvalid, 0)
    step(1)
    
    // 等待处理完成
    step(totalProcessingCycles + 10)  // 额外10个周期确保完成
    
    var ref = fft(a)
    var errorOne: Double = 0
    var error1: Double = 0
    var ovNum1: Int = 0
    var eps: Double = 1e-9
    
    // 第三阶段：读取FFTLength个复数（等价于原始输出循环）
    println("阶段3: 读取" + FFTLength + "个输出复数...")
    for (i <- 0 until FFTLength) {
      var ref1 = ref(i)
      
      // 等价于原有的 var d1 = peek(c.io.dout.bits)
      val data64 = axiRead()
      val reRaw = data64 & 0xFFFFFFFFL
      val imRaw = (data64 >> 32) & 0xFFFFFFFFL
      
      val reSigned = if (reRaw >= (1L << 31)) (reRaw - (1L << 32)) else reRaw
      val imSigned = if (imRaw >= (1L << 31)) (imRaw - (1L << 32)) else imRaw
      
      // 只打印前3个和后3个
      if (i < 3 || i >= FFTLength - 3) {
        println("输出[" + i + "]: re=" + reSigned + ", im=" + imSigned)
        println("期望[" + i + "]: re=" + ref1.re + ", im=" + ref1.im)
        val reFloat = (2.0 * reSigned.toDouble / bound)
        val imFloat = (2.0 * imSigned.toDouble / bound)
        println("转换[" + i + "]: re=" + reFloat + ", im=" + imFloat)
      }
      
      // 保持原有的误差计算逻辑
      error1 = math.abs((((2 * reSigned.toDouble / bound) - ref1.re) / (ref1.re + eps) + 
                        ((2 * imSigned.toDouble / bound) - ref1.im) / (ref1.im + eps)) / 2.0)
      
      if (error1 <= 0.5) {
        errorOne += error1
      } else {
        ovNum1 += 1
      }
      
      // 等价于原有的 step(1)
      step(1)
    }
    
    errorOne = if((FFTLength - ovNum1) > 0) errorOne / (FFTLength - ovNum1) else 0
    ovNum += ovNum1
    error += errorOne
    var errorOnePercent = errorOne * 100
    
    println("第" + (t+1) + "次测试完成:")
    println("  错误率: " + errorOnePercent + "%")
    println("  溢出数量: " + ovNum1)
    printf("In this sample, Error rate: %.2f%% | number of ovs: %d\n", errorOnePercent, ovNum1)
  }
  
  println("\nFFT测试完成 - 严格按原始逻辑执行")
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
