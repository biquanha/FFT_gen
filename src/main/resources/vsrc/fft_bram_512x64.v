// FFT Buffer BRAM: 512 entries x 64 bits
// 单端口读写RAM，1周期读延迟（用于BRAM推断）
module fft_bram_512x64(
  input RW0_clk,
  input [8:0] RW0_addr,      // 9位地址 -> 512 entries
  input RW0_en,
  input RW0_wmode,           // 1=write, 0=read
  input [63:0] RW0_wdata,    // 64位写数据
  output [63:0] RW0_rdata    // 64位读数据
);

  reg reg_RW0_ren;
  reg [8:0] reg_RW0_addr;

  // 声明为reg以便Vivado推断为BRAM
  (* ram_style = "block" *) reg [63:0] ram [511:0];

  `ifdef RANDOMIZE_MEM_INIT
    integer initvar;
    initial begin
      #`RANDOMIZE_DELAY begin end
      for (initvar = 0; initvar < 512; initvar = initvar+1)
        ram[initvar] = {2 {$random}};
      reg_RW0_addr = {1 {$random}};
    end
  `endif

  // 读使能寄存器
  always @(posedge RW0_clk)
    reg_RW0_ren <= RW0_en && !RW0_wmode;

  // 读地址寄存器（实现1周期读延迟）
  always @(posedge RW0_clk)
    if (RW0_en && !RW0_wmode)
      reg_RW0_addr <= RW0_addr;

  // 写操作（同步写）
  always @(posedge RW0_clk)
    if (RW0_en && RW0_wmode)
      ram[RW0_addr] <= RW0_wdata;

  `ifdef RANDOMIZE_GARBAGE_ASSIGN
  reg [63:0] RW0_random;
  `ifdef RANDOMIZE_MEM_INIT
    initial begin
      #`RANDOMIZE_DELAY begin end
      RW0_random = {2{$random}};
      reg_RW0_ren = RW0_random[0];
    end
  `endif
  always @(posedge RW0_clk) RW0_random <= {2{$random}};
  assign RW0_rdata = reg_RW0_ren ? ram[reg_RW0_addr] : RW0_random[63:0];
  `else
  // 读操作（1周期延迟）
  assign RW0_rdata = ram[reg_RW0_addr];
  `endif

endmodule
