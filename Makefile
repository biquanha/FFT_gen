# FFT算法验证 Makefile
# 专注于Chisel数据导出和C程序验证

CC = gcc
CFLAGS = -Wall -Wextra -std=c99 -O2 -g -D_GNU_SOURCE
LDFLAGS = -lm

# 目标程序
TARGET = fft_verify
SOURCE = fft_algorithm_verify.c

# 数据文件
CHISEL_CONFIG = fft_config.txt
CHISEL_INPUT = fft_input_data.txt
CHISEL_OUTPUT = fft_output_data.txt
CHISEL_SUMMARY = fft_test_summary.txt

# 生成文件
LOG_FILE = fft_verification.log

.PHONY: all clean verify check-data help show-results

# 默认目标
all: $(TARGET)

# 编译验证程序
$(TARGET): $(SOURCE)
	$(CC) $(CFLAGS) -o $(TARGET) $(SOURCE) $(LDFLAGS)
	@echo "✅ FFT验证程序编译完成"

# 检查Chisel导出的数据文件
check-data:
	@echo "检查Chisel导出文件..."
	@if [ ! -f $(CHISEL_CONFIG) ]; then \
		echo "❌ 缺少配置文件: $(CHISEL_CONFIG)"; \
		echo "请先运行修改后的Chisel测试"; \
		echo ""; \
		echo "Chisel测试应该生成以下文件:"; \
		echo "  - fft_config.txt"; \
		echo "  - fft_input_data.txt"; \
		echo "  - fft_output_data.txt"; \
		echo "  - fft_test_summary.txt"; \
		exit 1; \
	fi
	@if [ ! -f $(CHISEL_INPUT) ]; then \
		echo "❌ 缺少输入数据: $(CHISEL_INPUT)"; \
		exit 1; \
	fi
	@if [ ! -f $(CHISEL_OUTPUT) ]; then \
		echo "❌ 缺少输出数据: $(CHISEL_OUTPUT)"; \
		exit 1; \
	fi
	@echo "✅ Chisel数据文件检查通过"
	@echo ""
	@echo "数据文件概览:"
	@echo "配置文件内容:"
	@head -10 $(CHISEL_CONFIG) | sed 's/^/  /'
	@echo ""
	@echo "输入数据示例 (前3行):"
	@head -6 $(CHISEL_INPUT) | tail -3 | sed 's/^/  /'
	@echo ""
	@echo "输出数据示例 (前3行):"
	@head -6 $(CHISEL_OUTPUT) | tail -3 | sed 's/^/  /'

# 运行算法验证
verify: $(TARGET) check-data
	@echo ""
	@echo "=== 开始FFT算法验证 ==="
	@echo ""
	./$(TARGET)
	@echo ""
	@if [ $$? -eq 0 ]; then \
		echo "🎉 算法验证成功！"; \
		echo ""; \
		echo "下一步可以:"; \
		echo "1. 查看详细结果: make show-results"; \
		echo "2. 进行硬件测试准备"; \
	else \
		echo "❌ 算法验证失败！"; \
		echo ""; \
		echo "排查建议:"; \
		echo "1. 检查FFT实现是否正确"; \
		echo "2. 查看日志: cat $(LOG_FILE)"; \
		echo "3. 验证Chisel数据: make check-data"; \
	fi

# 显示详细结果
show-results:
	@echo "=== FFT验证结果详情 ==="
	@echo ""
	@if [ -f $(LOG_FILE) ]; then \
		echo "📊 误差统计:"; \
		echo ""; \
		grep "iter=.*error=" $(LOG_FILE) | head -5 | while read line; do \
			echo "  $$line"; \
		done; \
		echo "  ..."; \
		echo ""; \
		echo "📈 误差分布:"; \
		grep "iter=.*error=" $(LOG_FILE) | awk -F'error=' '{print $$2}' | awk '{sum+=$$1; count++; if($$1>max) max=$$1; if(min=="" || $$1<min) min=$$1} END {printf "  平均误差: %.6f\n  最大误差: %.6f\n  最小误差: %.6f\n  总样本数: %d\n", sum/count, max, min, count}'; \
	else \
		echo "❌ 未找到日志文件，请先运行 make verify"; \
	fi
	@echo ""
	@if [ -f $(CHISEL_SUMMARY) ]; then \
		echo "📋 Chisel测试总结:"; \
		cat $(CHISEL_SUMMARY) | sed 's/^/  /'; \
	fi

# 清理生成文件
clean:
	rm -f $(TARGET) $(LOG_FILE) *.o
	@echo "✅ 清理编译文件完成"

# 深度清理（包括Chisel生成的数据）
clean-all: clean
	rm -f $(CHISEL_CONFIG) $(CHISEL_INPUT) $(CHISEL_OUTPUT) $(CHISEL_SUMMARY)
	@echo "✅ 清理所有文件完成"

# 生成测试报告
report: 
	@echo "生成测试报告..."
	@echo "=== FFT算法验证报告 ===" > verification_report.txt
	@echo "生成时间: $(shell date)" >> verification_report.txt
	@echo "" >> verification_report.txt
	@if [ -f $(CHISEL_CONFIG) ]; then \
		echo "=== 测试配置 ===" >> verification_report.txt; \
		cat $(CHISEL_CONFIG) >> verification_report.txt; \
		echo "" >> verification_report.txt; \
	fi
	@if [ -f $(LOG_FILE) ]; then \
		echo "=== 验证结果统计 ===" >> verification_report.txt; \
		grep "验证状态" $(LOG_FILE) >> verification_report.txt 2>/dev/null || echo "未找到验证状态" >> verification_report.txt; \
		echo "" >> verification_report.txt; \
		echo "=== 详细误差数据 ===" >> verification_report.txt; \
		head -20 $(LOG_FILE) >> verification_report.txt; \
	fi
	@echo "✅ 报告已生成: verification_report.txt"

# 快速测试（编译并验证）
test: all verify

# 调试版本
debug: CFLAGS += -DDEBUG -O0 -ggdb3
debug: $(TARGET)
	@echo "✅ 调试版本编译完成"

# 内存检查
valgrind: $(TARGET) check-data
	valgrind --leak-check=full --show-leak-kinds=all ./$(TARGET)

# 帮助信息
help:
	@echo "FFT算法验证工具使用说明"
	@echo ""
	@echo "主要命令:"
	@echo "  make all         - 编译验证程序"
	@echo "  make verify      - 运行完整验证"
	@echo "  make test        - 快速测试 (编译+验证)"
	@echo ""
	@echo "检查命令:"
	@echo "  make check-data  - 检查Chisel导出文件"
	@echo "  make show-results - 显示详细验证结果"
	@echo "  make report      - 生成测试报告"
	@echo ""
	@echo "工具命令:"
	@echo "  make debug       - 编译调试版本"
	@echo "  make valgrind    - 内存泄漏检查"
	@echo "  make clean       - 清理编译文件"
	@echo "  make clean-all   - 清理所有文件"
	@echo ""
	@echo "使用流程:"
	@echo "  1. 修改Chisel测试代码并运行"
	@echo "  2. make check-data  # 检查生成的数据文件"
	@echo "  3. make verify      # 验证C语言FFT算法"
	@echo "  4. make show-results # 查看详细结果"

# 示例数据（如果没有Chisel数据时的测试数据）
generate-sample:
	@echo "生成示例数据文件（仅用于测试）..."
	@echo "FFT_LENGTH=8" > $(CHISEL_CONFIG)
	@echo "BINARY_POINT=16" >> $(CHISEL_CONFIG)
	@echo "ITER_NUM=2" >> $(CHISEL_CONFIG)
	@echo "RANDOM_SEED=12345" >> $(CHISEL_CONFIG)
	@echo "# FFT输入数据" > $(CHISEL_INPUT)
	@echo "0_0 1000 2000 0.030518 0.061035" >> $(CHISEL_INPUT)
	@echo "0_1 -1500 500 -0.045776 0.015259" >> $(CHISEL_INPUT)
	@echo "# FFT输出数据" > $(CHISEL_OUTPUT)
	@echo "0_0 1200 1800 0.036621 0.054932" >> $(CHISEL_OUTPUT)
	@echo "0_1 -1200 800 -0.036621 0.024414" >> $(CHISEL_OUTPUT)
	@echo "⚠️  已生成示例数据，仅用于测试编译和基本功能"