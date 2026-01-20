package FFT

import chisel3._
import chisel3.util._

// AXI-lite从机接口定义 - 64位数据宽度
class AXI4LiteSlaveBundle(addrWidth: Int = 32, dataWidth: Int = 64) extends Bundle {
  val awaddr  = Input(UInt(addrWidth.W))
  val awvalid = Input(Bool())
  val awready = Output(Bool())
  val wdata   = Input(UInt(dataWidth.W))
  val wstrb   = Input(UInt((dataWidth/8).W))
  val wvalid  = Input(Bool())
  val wready  = Output(Bool())
  val bresp   = Output(UInt(2.W))
  val bvalid  = Output(Bool())
  val bready  = Input(Bool())
  val araddr  = Input(UInt(addrWidth.W))
  val arvalid = Input(Bool())
  val arready = Output(Bool())
  val rdata   = Output(UInt(dataWidth.W))
  val rresp   = Output(UInt(2.W))
  val rvalid  = Output(Bool())
  val rready  = Input(Bool())
}

class TOP extends Module with HasDataConfig with HasElaborateConfig {
  val io = IO(new Bundle{
    val mode = if(supportIFFT) Some(Input(Bool())) else None
    val axi = new AXI4LiteSlaveBundle(32, 64)
  })

  private val enableHwDebug = sys.props.get("FFT_HW_DEBUG").exists(_.toBoolean)
  private def hwDebug(body: => Unit): Unit = if (enableHwDebug) body

  // 创建虚拟的DecoupledIO接口用于内部FFT连接
  val din = Wire(Flipped(DecoupledIO(new MyComplex)))
  val dout = Wire(DecoupledIO(new MyComplex))

  // FFT连接逻辑（保留小 FIFO 确保握手稳定）
  val FFT = Module(new FFTReorder)
  val fifoCmd = Wire(DecoupledIO(new MyComplex))
  fifoCmd.valid := FFT.io.dout_valid
  fifoCmd.bits := FFT.io.dOut
  val fifo = Queue(fifoCmd, 2 * FFTLength)

  fifo.ready := dout.ready
  din.ready := fifoCmd.ready
  FFT.io.din_valid := din.valid && din.ready
  FFT.io.dIn := din.bits
  dout.valid := fifo.valid
  dout.bits := fifo.bits
  
  // 四阶段状态机：INIT -> INPUT -> PROCESSING -> OUTPUT
  val sInit :: sInput :: sProcessing :: sOutput :: Nil = Enum(4)
  val fftState = RegInit(sInit)

  // 存储FFT输入和输出数据
  val inputMem = Mem(FFTLength, UInt(64.W))
  val outputMem = Mem(FFTLength, UInt(64.W))

  // 计数器
  val inputCount = RegInit(0.U(log2Ceil(FFTLength + 1).W))    // 接收的输入数量
  val outputCount = RegInit(0.U(log2Ceil(FFTLength + 1).W))   // 收集的输出数量
  val readCount = RegInit(0.U(log2Ceil(FFTLength + 1).W))     // AXI读取计数
  val processCycle = RegInit(0.U(log2Ceil(FFTLength + 10).W)) // 处理周期计数
  val initCount = RegInit(0.U(log2Ceil(FFTLength + 1).W))     // 初始化计数
  val inputComplete = RegInit(false.B)                        // 标记输入是否完成

  // 初始化阶段：预填充outputMem为0，避免第1次迭代读到未定义数据
  when(fftState === sInit) {
    outputMem(initCount) := 0.U
    initCount := initCount + 1.U
    when(initCount === (FFTLength - 1).U) {
      fftState := sInput
      hwDebug {
        printf("FFT_PHASE: Initialization complete\n")
      }
    }
  }
  
  // FFT输入控制 - 严格模拟原始逻辑
  din.valid := false.B
  din.bits.re := 0.S
  din.bits.im := 0.S
  
  // FFT输出控制 - 等价于原始的dout.ready=1
  dout.ready := true.B
  
  // FFT状态机
  switch(fftState) {
    is(sInput) {
      // 重置输入完成标记，为新一轮迭代做准备
      inputComplete := false.B

      // 输入阶段：接收FFTLength个复数，模拟原始的输入循环
      when(inputCount === FFTLength.U) {
        fftState := sProcessing
        processCycle := 0.U
        hwDebug {
          printf("FFT_PHASE: Input complete, starting processing\n")
        }
      }
    }
    
    is(sProcessing) {
      // 处理阶段：模拟原始的输入循环+等待时间
      processCycle := processCycle + 1.U

      // 前FFTLength个周期：逐个输入到FFT（模拟原始的step(1)循环）
      // 只有在inputComplete=false时才允许设置din.valid，防止重复输入
      when(processCycle < FFTLength.U && !inputComplete) {
        val inputData = inputMem(processCycle)
        din.bits.re := inputData(31, 0).asSInt
        din.bits.im := inputData(63, 32).asSInt

        // R2DIF等批量算法需要所有输入都valid，R2MDC只需第一个
        // 为了兼容两种模式，都设置为valid=1
        din.valid := true.B

        when(processCycle < 3.U) {
          hwDebug {
            printf("FFT_INPUT[%d]: re=%d, im=%d, valid=%d\n",
              processCycle, inputData(31, 0).asSInt, inputData(63, 32).asSInt, din.valid)
          }
        }

        // 标记输入完成
        when(processCycle === (FFTLength - 1).U) {
          inputComplete := true.B
          hwDebug {
            printf("FFT_PHASE: Input to FFT complete, waiting for output\n")
          }
        }
      }

      // 等待FFT输出完成：当outputMem被完全填充时转到sOutput
      when(outputCount === FFTLength.U) {
        fftState := sOutput
        hwDebug {
          printf("FFT_PHASE: Processing complete (outputMem filled), ready for output\n")
        }
      }
    }
    
    is(sOutput) {
      // 输出阶段：允许读取FFTLength个复数
      when(readCount === FFTLength.U) {
        fftState := sInput
        inputCount := 0.U
        outputCount := 0.U
        readCount := 0.U
        hwDebug {
          printf("FFT_PHASE: Output complete, ready for next input\n")
        }
      }
    }
  }
  
  // 收集FFT输出数据
  when(dout.valid && dout.ready) {
    val outputData = Cat(dout.bits.im.asUInt, dout.bits.re.asUInt)
    outputMem(outputCount) := outputData
    
    when(outputCount < 3.U) {
      hwDebug {
        printf("FFT_OUTPUT[%d]: re=%d, im=%d\n",
          outputCount, dout.bits.re.asSInt, dout.bits.im.asSInt)
      }
    }
    
    outputCount := outputCount + 1.U
  }
  
  // AXI状态机
  val sIdle :: sWrite :: sRead :: Nil = Enum(3)
  val axiState = RegInit(sIdle)
  
  val bvalidReg = RegInit(false.B)
  val rvalidReg = RegInit(false.B)
  val rdataReg = RegInit(0.U(64.W))
  
  // AXI信号控制：严格按阶段控制，同时等待FFT core就绪
  // sInit阶段拒绝所有AXI请求
  io.axi.awready := axiState === sIdle && fftState === sInput && inputCount < FFTLength.U
  io.axi.wready := axiState === sWrite
  io.axi.bvalid := bvalidReg
  io.axi.bresp := 0.U
  io.axi.arready := axiState === sIdle && fftState === sOutput && readCount < FFTLength.U
  io.axi.rvalid := rvalidReg
  io.axi.rdata := rdataReg
  io.axi.rresp := 0.U
  
  switch(axiState) {
    is(sIdle) {
      bvalidReg := false.B
      rvalidReg := false.B

      // 只在INPUT阶段且FFT不busy时接受写请求
      when(io.axi.awvalid && io.axi.awready && fftState === sInput) {
        axiState := sWrite
        when(inputCount < 3.U) {
          hwDebug {
            printf("AXI_WRITE_REQ[%d] in INPUT phase (FFT ready)\n", inputCount)
          }
        }
      }.elsewhen(io.axi.arvalid && io.axi.arready && fftState === sOutput) {
        // 只在OUTPUT阶段接受读请求
        axiState := sRead
        rdataReg := outputMem(readCount)
        rvalidReg := true.B

        when(readCount < 3.U) {
          hwDebug {
            printf("AXI_READ_REQ[%d] in OUTPUT phase, data=0x%x\n", readCount, outputMem(readCount))
          }
        }
      }
    }
    
    is(sWrite) {
      when(io.axi.wvalid && io.axi.wready) {
        axiState := sIdle
        bvalidReg := true.B
        
        // 存储输入数据
        inputMem(inputCount) := io.axi.wdata
        
        when(inputCount < 3.U) {
          hwDebug {
            printf("AXI_WRITE_DATA[%d] = 0x%x\n", inputCount, io.axi.wdata)
          }
        }
        
        inputCount := inputCount + 1.U
      }
    }
    
    is(sRead) {
      when(io.axi.rready && io.axi.rvalid) {
        axiState := sIdle
        rvalidReg := false.B
        
        when(readCount < 3.U) {
          hwDebug {
            printf("AXI_READ_COMPLETE[%d]\n", readCount)
          }
        }
        
        readCount := readCount + 1.U
      }
    }
  }
  
  // 写响应握手处理
  when(io.axi.bready && io.axi.bvalid) {
    bvalidReg := false.B
  }
}

// Verilator会保留TOP作为内部名称，测试时使用此别名避免冲突
class FFTTop extends TOP
