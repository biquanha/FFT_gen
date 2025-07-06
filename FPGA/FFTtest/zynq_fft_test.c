/*
 * Zynq FFT测试框架
 * 专为Zynq平台设计，纯内存操作，无文件依赖
 * 功能：随机激励生成，软件模型 vs 硬件模型对比
 */
/*
make all 编译
ake test运行
*/
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <string.h>
#include <assert.h>
#include <stdint.h>
#include <time.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// === Zynq平台配置 ===
#define FFT_LENGTH       512     // FFT长度（可根据硬件调整）
#define BINARY_POINT     16      // 定点数精度
#define TEST_ITERATIONS  10      // 测试迭代次数
#define RANDOM_SEED      12345   // 随机数种子

// Zynq硬件地址配置（根据实际硬件修改）
#define ZYNQ_FFT_BASE_ADDR    0x43C00000
#define ZYNQ_FFT_INPUT_OFFSET 0x0000
#define ZYNQ_FFT_OUTPUT_OFFSET 0x1000
#define ZYNQ_FFT_CTRL_OFFSET  0x2000
#define ZYNQ_FFT_STATUS_OFFSET 0x2004

// 复数结构体
typedef struct {
    double re;
    double im;
} Complex;

// 测试配置
typedef struct {
    int fft_length;
    int binary_point;
    int test_iterations;
    uint32_t random_seed;
    double bound;
    double error_threshold;  // 误差阈值
} FFTConfig;

// 测试结果统计
typedef struct {
    int iteration;
    double avg_error;
    double max_error;
    int large_error_count;
    double sw_time_ms;
    double hw_time_ms;
    int passed;
} TestResult;

// FFT实现函数指针
typedef int (*fft_func_t)(Complex *input, Complex *output, int length);

// 全局变量
FFTConfig g_config;
static uint32_t g_rand_state;

// === 随机数生成器（Chisel兼容） ===
void init_random(uint32_t seed) {
    g_rand_state = seed;
    printf("初始化随机数生成器，种子: %u\n", seed);
}

uint32_t next_random() {
    g_rand_state = (g_rand_state * 1103515245U + 12345U) & 0x7fffffffU;
    return g_rand_state;
}

int next_int(int bound) {
    if (bound <= 0) return 0;
    return (int)(next_random() % bound);
}

// === 复数运算 ===
Complex complex_add(Complex a, Complex b) {
    Complex result = {a.re + b.re, a.im + b.im};
    return result;
}

Complex complex_sub(Complex a, Complex b) {
    Complex result = {a.re - b.re, a.im - b.im};
    return result;
}

Complex complex_mul(Complex a, Complex b) {
    Complex result = {
        a.re * b.re - a.im * b.im,
        a.re * b.im + a.im * b.re
    };
    return result;
}

Complex complex_exp(double angle) {
    Complex result = {cos(angle), sin(angle)};
    return result;
}

// === 软件FFT参考模型 ===
void fft_recursive(Complex *x, int start, int n, int stride, Complex *result) {
    if (n == 1) {
        result[0] = x[start];
        return;
    }
    
    Complex *left = malloc(n/2 * sizeof(Complex));
    Complex *right = malloc(n/2 * sizeof(Complex));
    
    if (!left || !right) {
        printf("错误: FFT内存分配失败\n");
        exit(1);
    }
    
    fft_recursive(x, start, n/2, 2*stride, left);
    fft_recursive(x, start + stride, n/2, 2*stride, right);
    
    for (int k = 0; k < n/2; k++) {
        Complex t = left[k];
        double arg = -2.0 * M_PI * k / n;
        Complex w = complex_exp(arg);
        Complex twiddle = complex_mul(w, right[k]);
        
        result[k] = complex_add(t, twiddle);
        result[k + n/2] = complex_sub(t, twiddle);
    }
    
    free(left);
    free(right);
}

int software_fft_model(Complex *input, Complex *output, int length) {
    assert(length > 0 && (length & (length - 1)) == 0);
    fft_recursive(input, 0, length, 1, output);
    return 0;
}

// === 硬件FFT模型（Zynq实现接口） ===
int hardware_fft_model(Complex *input, Complex *output, int length) {
    printf("调用Zynq硬件FFT (长度: %d)\n", length);
    
    // TODO: 替换为实际的Zynq硬件FFT实现
    // 以下是实现模板，需要根据具体硬件修改
    
    /*
    // 1. 检查硬件状态
    volatile uint32_t *status_reg = (volatile uint32_t*)(ZYNQ_FFT_BASE_ADDR + ZYNQ_FFT_STATUS_OFFSET);
    if ((*status_reg & 0x1) == 0) {
        printf("错误: FFT硬件未就绪\n");
        return -1;
    }
    
    // 2. 写入输入数据到硬件
    volatile uint32_t *input_base = (volatile uint32_t*)(ZYNQ_FFT_BASE_ADDR + ZYNQ_FFT_INPUT_OFFSET);
    for (int i = 0; i < length; i++) {
        // 转换为定点数格式
        int32_t re_fixed = (int32_t)(input[i].re * (1 << BINARY_POINT));
        int32_t im_fixed = (int32_t)(input[i].im * (1 << BINARY_POINT));
        
        input_base[i*2] = (uint32_t)re_fixed;
        input_base[i*2+1] = (uint32_t)im_fixed;
    }
    
    // 3. 启动FFT计算
    volatile uint32_t *ctrl_reg = (volatile uint32_t*)(ZYNQ_FFT_BASE_ADDR + ZYNQ_FFT_CTRL_OFFSET);
    *ctrl_reg = 0x1;  // 启动
    
    // 4. 等待计算完成
    int timeout = 100000;
    while (timeout-- > 0) {
        if (*status_reg & 0x2) {  // 完成标志
            break;
        }
    }
    
    if (timeout <= 0) {
        printf("错误: FFT硬件计算超时\n");
        return -1;
    }
    
    // 5. 读取输出数据
    volatile uint32_t *output_base = (volatile uint32_t*)(ZYNQ_FFT_BASE_ADDR + ZYNQ_FFT_OUTPUT_OFFSET);
    for (int i = 0; i < length; i++) {
        int32_t re_fixed = (int32_t)output_base[i*2];
        int32_t im_fixed = (int32_t)output_base[i*2+1];
        
        output[i].re = (double)re_fixed / (1 << BINARY_POINT);
        output[i].im = (double)im_fixed / (1 << BINARY_POINT);
    }
    
    // 6. 复位硬件
    *ctrl_reg = 0x0;
    */
    
    // 暂时使用软件实现作为占位符
    printf("  注意: 当前使用软件模型作为占位符\n");
    printf("  请在上述注释区域添加实际的Zynq硬件访问代码\n");
    return software_fft_model(input, output, length);
}

// === 用户自定义FFT模型 ===
int custom_fft_model(Complex *input, Complex *output, int length) {
    printf("调用自定义FFT模型 (长度: %d)\n", length);
    
    // TODO: 在这里添加你的自定义FFT实现
    // 例如：优化的软件FFT、第三方库FFT等
    
    printf("  注意: 当前使用软件模型作为占位符\n");
    return software_fft_model(input, output, length);
}

// === 测试数据生成 ===
void generate_random_data(Complex *data, int length) {
    printf("生成随机测试数据 (长度: %d, 种子: %u)\n", length, g_config.random_seed);
    
    for (int i = 0; i < length; i++) {
        // 按照Chisel逻辑生成随机数
        int re_int = -(int)(g_config.bound) / 2 + next_int((int)(g_config.bound));
        int im_int = -(int)(g_config.bound) / 2 + next_int((int)(g_config.bound));
        
        data[i].re = 2.0 * re_int / g_config.bound;
        data[i].im = 2.0 * im_int / g_config.bound;
        
        // 显示前几个数据点
        if (i < 3) {
            printf("  数据[%d]: (%d, %d) -> (%.6f, %.6f)\n", 
                   i, re_int, im_int, data[i].re, data[i].im);
        }
    }
}

// === 误差计算 ===
double calculate_relative_error(Complex *result, Complex *reference, int length, 
                               double *max_error, int *large_error_count) {
    double total_error = 0.0;
    int valid_count = 0;
    double eps = 1e-9;
    *max_error = 0.0;
    *large_error_count = 0;
    
    for (int i = 0; i < length; i++) {
        double re_error = fabs(reference[i].re) > eps ?
                         fabs((result[i].re - reference[i].re) / reference[i].re) :
                         fabs(result[i].re - reference[i].re);
        
        double im_error = fabs(reference[i].im) > eps ?
                         fabs((result[i].im - reference[i].im) / reference[i].im) :
                         fabs(result[i].im - reference[i].im);
        
        double point_error = (re_error + im_error) / 2.0;
        
        if (point_error > *max_error) {
            *max_error = point_error;
        }
        
        if (point_error < 0.5) {  // 排除异常大的误差
            total_error += point_error;
            valid_count++;
        } else {
            (*large_error_count)++;
        }
    }
    
    return valid_count > 0 ? total_error / valid_count : 0.0;
}

// === FFT模型对比测试 ===
TestResult compare_fft_models(const char *name1, fft_func_t func1,
                             const char *name2, fft_func_t func2,
                             Complex *input, int length, int iteration) {
    TestResult result = {0};
    result.iteration = iteration;
    
    Complex *output1 = malloc(length * sizeof(Complex));
    Complex *output2 = malloc(length * sizeof(Complex));
    
    printf("\n--- 迭代 %d: 对比 %s vs %s ---\n", iteration, name1, name2);
    
    // 执行第一个模型（通常是软件参考）
    clock_t start1 = clock();
    int ret1 = func1(input, output1, length);
    clock_t end1 = clock();
    result.sw_time_ms = ((double)(end1 - start1)) / CLOCKS_PER_SEC * 1000.0;
    
    // 执行第二个模型（通常是硬件模型）
    clock_t start2 = clock();
    int ret2 = func2(input, output2, length);
    clock_t end2 = clock();
    result.hw_time_ms = ((double)(end2 - start2)) / CLOCKS_PER_SEC * 1000.0;
    
    if (ret1 != 0 || ret2 != 0) {
        printf("错误: FFT执行失败 (%s: %d, %s: %d)\n", name1, ret1, name2, ret2);
        result.passed = 0;
        goto cleanup;
    }
    
    // 计算误差
    result.avg_error = calculate_relative_error(output1, output2, length, 
                                              &result.max_error, &result.large_error_count);
    
    // 判断是否通过
    result.passed = (result.avg_error < g_config.error_threshold && result.large_error_count == 0);
    
    // 显示结果
    printf("测试结果:\n");
    printf("  平均误差: %.6f%% (阈值: %.3f%%)\n", result.avg_error * 100.0, g_config.error_threshold * 100.0);
    printf("  最大误差: %.6f%%\n", result.max_error * 100.0);
    printf("  大误差点数: %d/%d\n", result.large_error_count, length);
    printf("  %s时间: %.3f ms\n", name1, result.sw_time_ms);
    printf("  %s时间: %.3f ms\n", name2, result.hw_time_ms);
    printf("  状态: %s\n", result.passed ? "✅ 通过" : "❌ 失败");
    
    // 显示前几个数据点的对比
    printf("详细对比 (前3个点):\n");
    for (int i = 0; i < 3 && i < length; i++) {
        printf("  [%d]: %s=(%.6f,%.6f), %s=(%.6f,%.6f)\n",
               i, name1, output1[i].re, output1[i].im,
               name2, output2[i].re, output2[i].im);
    }

cleanup:
    free(output1);
    free(output2);
    return result;
}

// === 配置初始化 ===
void init_test_config() {
    g_config.fft_length = FFT_LENGTH;
    g_config.binary_point = BINARY_POINT;
    g_config.test_iterations = TEST_ITERATIONS;
    g_config.random_seed = RANDOM_SEED;
    g_config.bound = pow(2.0, g_config.binary_point);
    g_config.error_threshold = 0.01;  // 1%误差阈值
    
    printf("=== Zynq FFT测试框架配置 ===\n");
    printf("FFT长度: %d\n", g_config.fft_length);
    printf("二进制点: %d\n", g_config.binary_point);
    printf("测试迭代: %d\n", g_config.test_iterations);
    printf("随机种子: %u\n", g_config.random_seed);
    printf("数据范围: ±%.0f\n", g_config.bound/2);
    printf("误差阈值: %.2f%%\n", g_config.error_threshold * 100.0);
    printf("\n");
}

// === 主测试函数 ===
void run_fft_comparison_test() {
    printf("开始FFT模型对比测试...\n\n");
    
    // 初始化随机数生成器
    init_random(g_config.random_seed);
    
    // 分配测试数据内存
    Complex *test_data = malloc(g_config.fft_length * sizeof(Complex));
    if (!test_data) {
        printf("错误: 无法分配测试数据内存\n");
        return;
    }
    
    // 统计变量
    int total_passed = 0;
    double total_avg_error = 0.0;
    double total_sw_time = 0.0;
    double total_hw_time = 0.0;
    
    // 进行多次迭代测试
    for (int iter = 0; iter < g_config.test_iterations; iter++) {
        // 重新初始化随机数（可选：保证每次迭代数据不同）
        init_random(g_config.random_seed + iter);
        
        // 生成新的随机测试数据
        generate_random_data(test_data, g_config.fft_length);
        
        // 软件模型 vs 硬件模型
        TestResult result1 = compare_fft_models("软件模型", software_fft_model,
                                               "硬件模型", hardware_fft_model,
                                               test_data, g_config.fft_length, iter + 1);
        
        // 软件模型 vs 自定义模型
        TestResult result2 = compare_fft_models("软件模型", software_fft_model,
                                               "自定义模型", custom_fft_model,
                                               test_data, g_config.fft_length, iter + 1);
        
        // 统计结果
        if (result1.passed) total_passed++;
        total_avg_error += result1.avg_error;
        total_sw_time += result1.sw_time_ms;
        total_hw_time += result1.hw_time_ms;
        
        printf("\n");
    }
    
    // 输出总体统计
    printf("=== 测试总结 ===\n");
    printf("通过的测试: %d/%d (%.1f%%)\n", 
           total_passed, g_config.test_iterations, 
           100.0 * total_passed / g_config.test_iterations);
    printf("平均误差: %.6f%%\n", (total_avg_error / g_config.test_iterations) * 100.0);
    printf("平均软件时间: %.3f ms\n", total_sw_time / g_config.test_iterations);
    printf("平均硬件时间: %.3f ms\n", total_hw_time / g_config.test_iterations);
    
    if (total_hw_time > 0) {
        printf("性能提升: %.2fx\n", total_sw_time / total_hw_time);
    }
    
    if (total_passed == g_config.test_iterations) {
        printf("\n🎉 所有测试通过！FFT硬件模型工作正常。\n");
    } else {
        printf("\n⚠️  部分测试失败，请检查硬件实现或调整误差阈值。\n");
    }
    
    free(test_data);
}

// === 命令行参数处理 ===
void print_usage(const char *program_name) {
    printf("Zynq FFT测试框架\n");
    printf("用法: %s [选项]\n", program_name);
    printf("选项:\n");
    printf("  -h, --help        显示帮助信息\n");
    printf("  -l LENGTH         设置FFT长度 (必须是2的幂)\n");
    printf("  -i ITERATIONS     设置测试迭代次数\n");
    printf("  -s SEED           设置随机数种子\n");
    printf("  -t THRESHOLD      设置误差阈值 (0.0-1.0)\n");
    printf("\n");
    printf("示例:\n");
    printf("  %s                    # 使用默认配置\n", program_name);
    printf("  %s -l 1024 -i 20      # FFT长度1024，20次迭代\n", program_name);
    printf("  %s -t 0.05            # 设置5%%误差阈值\n", program_name);
}

// === 主函数 ===
int main(int argc, char *argv[]) {
    printf("=== Zynq FFT测试框架 ===\n");
    printf("专为Zynq平台设计的FFT验证框架\n");
    printf("功能: 随机激励生成 + 软件/硬件FFT模型对比\n\n");
    
    // 初始化默认配置
    init_test_config();
    
    // 解析命令行参数
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "-h") == 0 || strcmp(argv[i], "--help") == 0) {
            print_usage(argv[0]);
            return 0;
        } else if (strcmp(argv[i], "-l") == 0 && i + 1 < argc) {
            int length = atoi(argv[++i]);
            if (length > 0 && (length & (length - 1)) == 0) {
                g_config.fft_length = length;
                printf("设置FFT长度: %d\n", length);
            } else {
                printf("错误: FFT长度必须是2的幂\n");
                return 1;
            }
        } else if (strcmp(argv[i], "-i") == 0 && i + 1 < argc) {
            int iterations = atoi(argv[++i]);
            if (iterations > 0) {
                g_config.test_iterations = iterations;
                printf("设置测试迭代次数: %d\n", iterations);
            }
        } else if (strcmp(argv[i], "-s") == 0 && i + 1 < argc) {
            uint32_t seed = (uint32_t)atoi(argv[++i]);
            g_config.random_seed = seed;
            printf("设置随机数种子: %u\n", seed);
        } else if (strcmp(argv[i], "-t") == 0 && i + 1 < argc) {
            double threshold = atof(argv[++i]);
            if (threshold >= 0.0 && threshold <= 1.0) {
                g_config.error_threshold = threshold;
                printf("设置误差阈值: %.3f%%\n", threshold * 100.0);
            } else {
                printf("错误: 误差阈值必须在0.0-1.0之间\n");
                return 1;
            }
        }
    }
    
    // 运行主测试
    run_fft_comparison_test();
    
    return 0;
}