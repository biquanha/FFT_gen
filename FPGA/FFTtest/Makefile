# Zynq FFT测试框架 Makefile
# 专为Zynq平台设计，纯内存操作，无文件依赖

CC = gcc
ARM_CC = arm-linux-gnueabihf-gcc  # ARM交叉编译器
CFLAGS = -Wall -Wextra -std=c99 -O2 -g -D_GNU_SOURCE
LDFLAGS = -lm

# 目标程序
TARGET = zynq_fft_test
SOURCE = zynq_fft_test.c

# 默认配置
DEFAULT_FFT_LENGTH = 512
DEFAULT_ITERATIONS = 10
DEFAULT_THRESHOLD = 0.01

.PHONY: all clean test test-quick test-long arm-build help install

# 默认目标：本地编译（用于开发和测试）
all: $(TARGET)
	@echo "✅ Zynq FFT测试程序编译完成 (本地版本)"
	@echo "使用 'make test' 运行基本测试"
	@echo "使用 'make arm-build' 进行ARM交叉编译"

# 本地编译
$(TARGET): $(SOURCE)
	$(CC) $(CFLAGS) -o $(TARGET) $(SOURCE) $(LDFLAGS)

# ARM交叉编译（用于Zynq部署）
arm-build: $(SOURCE)
	$(ARM_CC) $(CFLAGS) -o $(TARGET) $(SOURCE) $(LDFLAGS)
	@echo "✅ ARM版本编译完成，可部署到Zynq"
	@echo "部署命令: scp $(TARGET) root@zynq_ip:/tmp/"

# === 测试命令 ===

# 基本测试
test: $(TARGET)
	@echo "=== 运行基本FFT测试 ==="
	./$(TARGET)

# 快速测试（较少迭代）
test-quick: $(TARGET)
	@echo "=== 快速测试 (5次迭代) ==="
	./$(TARGET) -i 5

# 长时间测试（更多迭代）
test-long: $(TARGET)
	@echo "=== 长时间测试 (50次迭代) ==="
	./$(TARGET) -i 50

# 不同FFT长度测试
test-lengths: $(TARGET)
	@echo "=== 测试不同FFT长度 ==="
	@for len in 64 128 256 512 1024; do \
		echo "测试FFT长度: $$len"; \
		./$(TARGET) -l $$len -i 5; \
		echo ""; \
	done

# 不同误差阈值测试
test-thresholds: $(TARGET)
	@echo "=== 测试不同误差阈值 ==="
	@for threshold in 0.001 0.01 0.05 0.1; do \
		echo "测试误差阈值: $$threshold"; \
		./$(TARGET) -t $$threshold -i 5; \
		echo ""; \
	done

# 性能基准测试
benchmark: $(TARGET)
	@echo "=== FFT性能基准测试 ==="
	@echo "测试配置: 多种长度, 20次迭代"
	@for len in 128 256 512 1024; do \
		echo "=== FFT长度: $$len ==="; \
		./$(TARGET) -l $$len -i 20; \
		echo ""; \
	done

# === 开发和调试 ===

# 调试版本
debug: CFLAGS += -DDEBUG -O0 -ggdb3
debug: $(TARGET)
	@echo "✅ 调试版本编译完成"

# 内存检查
valgrind: $(TARGET)
	@echo "=== 内存泄漏检查 ==="
	valgrind --leak-check=full --show-leak-kinds=all ./$(TARGET) -i 3

# 静态分析
analyze: $(SOURCE)
	@echo "=== 静态代码分析 ==="
	@which cppcheck > /dev/null && cppcheck --enable=all $(SOURCE) || echo "未安装cppcheck"

# === Zynq部署相关 ===

# 创建Zynq部署包
package: arm-build
	@echo "创建Zynq部署包..."
	mkdir -p zynq_deploy
	cp $(TARGET) zynq_deploy/
	@echo "#!/bin/bash" > zynq_deploy/run_test.sh
	@echo "echo '=== Zynq FFT硬件测试 ==='" >> zynq_deploy/run_test.sh
	@echo "echo '基本测试:'" >> zynq_deploy/run_test.sh
	@echo "./$(TARGET)" >> zynq_deploy/run_test.sh
	@echo "echo ''" >> zynq_deploy/run_test.sh
	@echo "echo '长时间测试:'" >> zynq_deploy/run_test.sh
	@echo "./$(TARGET) -i 30" >> zynq_deploy/run_test.sh
	chmod +x zynq_deploy/run_test.sh
	@echo "# Zynq FFT测试说明" > zynq_deploy/README.md
	@echo "1. 运行基本测试: ./$(TARGET)" >> zynq_deploy/README.md
	@echo "2. 运行自动测试脚本: ./run_test.sh" >> zynq_deploy/README.md
	@echo "3. 自定义参数: ./$(TARGET) -h" >> zynq_deploy/README.md
	tar -czf zynq_fft_test.tar.gz zynq_deploy/
	@echo "✅ 部署包已创建: zynq_fft_test.tar.gz"

# 远程部署到Zynq（需要设置ZYNQ_IP环境变量）
deploy: package
	@if [ -z "$(ZYNQ_IP)" ]; then \
		echo "错误: 请设置ZYNQ_IP环境变量"; \
		echo "示例: export ZYNQ_IP=192.168.1.100"; \
		exit 1; \
	fi
	@echo "部署到Zynq设备: $(ZYNQ_IP)"
	scp zynq_fft_test.tar.gz root@$(ZYNQ_IP):/tmp/
	ssh root@$(ZYNQ_IP) "cd /tmp && tar -xzf zynq_fft_test.tar.gz"
	@echo "✅ 部署完成"
	@echo "登录Zynq运行: ssh root@$(ZYNQ_IP) 'cd /tmp/zynq_deploy && ./run_test.sh'"

# 远程运行测试
remote-test:
	@if [ -z "$(ZYNQ_IP)" ]; then \
		echo "错误: 请设置ZYNQ_IP环境变量"; \
		exit 1; \
	fi
	@echo "在Zynq设备上运行FFT测试..."
	ssh root@$(ZYNQ_IP) "cd /tmp/zynq_deploy && ./$(TARGET)"

# === 清理 ===

clean:
	rm -f $(TARGET) *.o core
	@echo "✅ 清理编译文件完成"

clean-all: clean
	rm -rf zynq_deploy zynq_fft_test.tar.gz
	@echo "✅ 清理所有文件完成"

# === 帮助和配置 ===

help:
	@echo "Zynq FFT测试框架 - Makefile帮助"
	@echo ""
	@echo "🚀 编译目标:"
	@echo "  make all          - 本地编译（开发测试用）"
	@echo "  make arm-build    - ARM交叉编译（Zynq部署用）"
	@echo "  make debug        - 调试版本编译"
	@echo ""
	@echo "🧪 测试命令:"
	@echo "  make test         - 基本测试"
	@echo "  make test-quick   - 快速测试（5次迭代）"
	@echo "  make test-long    - 长时间测试（50次迭代）"
	@echo "  make test-lengths - 测试不同FFT长度"
	@echo "  make benchmark    - 性能基准测试"
	@echo ""
	@echo "🔧 开发工具:"
	@echo "  make valgrind     - 内存泄漏检查"
	@echo "  make analyze      - 静态代码分析"
	@echo ""
	@echo "📦 Zynq部署:"
	@echo "  make package      - 创建部署包"
	@echo "  make deploy       - 部署到Zynq (需要ZYNQ_IP环境变量)"
	@echo "  make remote-test  - 远程运行测试"
	@echo ""
	@echo "🧹 清理:"
	@echo "  make clean        - 清理编译文件"
	@echo "  make clean-all    - 清理所有文件"
	@echo ""
	@echo "💡 使用示例:"
	@echo "  make test                    # 本地基本测试"
	@echo "  make arm-build              # 为Zynq交叉编译"
	@echo "  export ZYNQ_IP=192.168.1.100"
	@echo "  make deploy                 # 部署到Zynq"
	@echo "  make remote-test            # 远程运行测试"
	@echo ""
	@echo "🔧 程序参数:"
	@echo "  ./$(TARGET) -h              # 显示程序帮助"
	@echo "  ./$(TARGET) -l 1024         # 设置FFT长度"
	@echo "  ./$(TARGET) -i 20           # 设置迭代次数"
	@echo "  ./$(TARGET) -t 0.05         # 设置误差阈值(5%)"

# 显示当前配置
show-config:
	@echo "=== 当前编译配置 ==="
	@echo "编译器: $(CC)"
	@echo "ARM编译器: $(ARM_CC)"
	@echo "编译选项: $(CFLAGS)"
	@echo "链接选项: $(LDFLAGS)"
	@echo ""
	@echo "=== 默认测试配置 ==="
	@echo "FFT长度: $(DEFAULT_FFT_LENGTH)"
	@echo "测试迭代: $(DEFAULT_ITERATIONS)"
	@echo "误差阈值: $(DEFAULT_THRESHOLD)"

# 检查依赖
check-deps:
	@echo "检查编译环境..."
	@which $(CC) > /dev/null || (echo "❌ 缺少编译器: $(CC)" && exit 1)
	@echo "✅ 本地编译器: $(CC)"
	@which $(ARM_CC) > /dev/null && echo "✅ ARM编译器: $(ARM_CC)" || echo "⚠️  ARM编译器未找到: $(ARM_CC)"
	@echo "数学库: -lm"

# 快速开始（推荐）
quick-start: all test-quick
	@echo ""
	@echo "🎉 快速开始完成！"
	@echo ""
	@echo "下一步:"
	@echo "1. 如需部署到Zynq: make arm-build"
	@echo "2. 如需更多测试: make test-long"
	@echo "3. 如需查看帮助: make help"

# 安装到系统（可选）
install: arm-build
	@echo "安装到系统..."
	sudo cp $(TARGET) /usr/local/bin/
	sudo chmod +x /usr/local/bin/$(TARGET)
	@echo "✅ 安装完成: /usr/local/bin/$(TARGET)"