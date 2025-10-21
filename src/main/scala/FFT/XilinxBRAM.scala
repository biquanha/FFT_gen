package FFT

import chisel3._
import chisel3.util._
import chisel3.experimental._

// FFT BRAM BlackBox: 512x64 单端口读写RAM
// 匹配fft_bram_512x64.v模板
class FFT_BRAM_512x64 extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val RW0_clk = Input(Clock())
    val RW0_addr = Input(UInt(9.W))      // 512 entries = 2^9
    val RW0_en = Input(Bool())
    val RW0_wmode = Input(Bool())        // 1=write, 0=read
    val RW0_wdata = Input(UInt(64.W))    // 64-bit data (32-bit re + 32-bit im)
    val RW0_rdata = Output(UInt(64.W))
  })

  addResource("/vsrc/fft_bram_512x64.v")
}

// Chisel Wrapper: 提供类似SyncReadMem的接口
// 注意：这是1周期读延迟的同步RAM
class FFT_BRAM_Wrapper extends Module with HasDataConfig with HasElaborateConfig {
  val io = IO(new Bundle {
    val writeEn = Input(Bool())
    val writeAddr = Input(UInt(log2Ceil(FFTLength).W))
    val writeData = Input(new MyComplex)
    val readAddr = Input(UInt(log2Ceil(FFTLength).W))
    val readData = Output(new MyComplex)
  })

  // 使用Mem（异步读）- Vivado也会推断为BRAM（当访问模式简单时）
  // 优势：仿真简单，无需处理1周期延迟；确保每周期只有一次写操作
  val ram = Mem(FFTLength, new MyComplex)

  // 写操作
  when(io.writeEn) {
    ram.write(io.writeAddr, io.writeData)
  }

  // 读操作（异步，立即可用）
  io.readData := ram.read(io.readAddr)
}
