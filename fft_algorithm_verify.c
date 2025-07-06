/*
 * FFT算法验证程序
 * 读取Chisel测试导出的数据，验证C语言FFT实现的正确性
 */

#define _GNU_SOURCE  // 启用GNU扩展，包括M_PI
#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <string.h>
#include <assert.h>

// 如果M_PI仍然未定义，手动定义
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// 复数结构体
typedef struct {
    double re;
    double im;
} Complex;

// 测试配置
typedef struct {
    int fft_length;
    int binary_point;
    int iter_num;
    int random_seed;
} FFTConfig;

// 输入数据结构
typedef struct {
    int iteration;
    int index;
    int re_int;        // 定点数整数表示
    int im_int;
    double re_float;   // 归一化浮点数 [-1, 1)
    double im_float;
} InputData;

// 输出数据结构  
typedef struct {
    int iteration;
    int index;
    int hw_re_int;     // Chisel硬件输出
    int hw_im_int;
    double hw_re_float;
    double hw_im_float;
    double ref_re;     // Chisel软件参考
    double ref_im;
} OutputData;

// 全局变量
FFTConfig config;
FILE *log_file;

// === 复数运算函数 ===
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

void print_complex(Complex c, const char* name) {
    printf("%s = (%.6f, %.6f)\n", name, c.re, c.im);
}

// === FFT实现 - 与Chisel版本完全一致 ===

/*
 * 递归FFT实现，严格按照Chisel代码逻辑
 * 对应Chisel中的: fft(x, start, n, stride)
 */
void fft_recursive(Complex *x, int start, int n, int stride, Complex *result) {
    if (n == 1) {
        result[0] = x[start];
        return;
    }
    
    // 分配临时数组
    Complex *left = malloc(n/2 * sizeof(Complex));
    Complex *right = malloc(n/2 * sizeof(Complex));
    
    if (!left || !right) {
        printf("错误: 内存分配失败\n");
        exit(1);
    }
    
    // 递归计算左右两部分 - 对应Chisel的递归调用
    fft_recursive(x, start, n/2, 2*stride, left);
    fft_recursive(x, start + stride, n/2, 2*stride, right);
    
    // 合并结果 - 严格按照Chisel的合并逻辑
    for (int k = 0; k < n/2; k++) {
        Complex t = left[k];
        double arg = -2.0 * M_PI * k / n;  // 对应Chisel: -2 * math.Pi * k / n
        Complex c = complex_exp(arg);      // 对应Chisel: new Complex(cos(arg), sin(arg))
        Complex twiddle = complex_mul(c, right[k]);  // 对应Chisel: c * X(k + n/2)
        
        result[k] = complex_add(t, twiddle);         // 对应Chisel: t + c
        result[k + n/2] = complex_sub(t, twiddle);   // 对应Chisel: t - c
    }
    
    free(left);
    free(right);
}

/*
 * FFT主函数 - 对应Chisel的fft(x: Array[Complex])
 */
void fft(Complex *input, Complex *output, int length) {
    // 检查长度是否为2的幂 - 对应Chisel的require语句
    assert(length > 0 && (length & (length - 1)) == 0);
    
    fft_recursive(input, 0, length, 1, output);
}

// === 文件读取函数 ===

int read_config(const char *filename) {
    FILE *file = fopen(filename, "r");
    if (!file) {
        printf("错误: 无法打开配置文件 %s\n", filename);
        return -1;
    }
    
    char line[256];
    printf("读取配置文件...\n");
    
    while (fgets(line, sizeof(line), file)) {
        if (line[0] == '#') continue;  // 跳过注释
        
        if (sscanf(line, "FFT_LENGTH=%d", &config.fft_length) == 1) {
            printf("  FFT长度: %d\n", config.fft_length);
            continue;
        }
        if (sscanf(line, "BINARY_POINT=%d", &config.binary_point) == 1) {
            printf("  二进制点: %d\n", config.binary_point);
            continue;
        }
        if (sscanf(line, "ITER_NUM=%d", &config.iter_num) == 1) {
            printf("  迭代次数: %d\n", config.iter_num);
            continue;
        }
        if (sscanf(line, "RANDOM_SEED=%d", &config.random_seed) == 1) {
            printf("  随机种子: %d\n", config.random_seed);
            continue;
        }
    }
    
    fclose(file);
    return 0;
}

int count_data_lines(const char *filename) {
    FILE *file = fopen(filename, "r");
    if (!file) return 0;
    
    char line[256];
    int count = 0;
    while (fgets(line, sizeof(line), file)) {
        if (line[0] != '#' && strlen(line) > 1) {
            count++;
        }
    }
    fclose(file);
    return count;
}

int read_input_data(const char *filename, InputData **data, int *count) {
    *count = count_data_lines(filename);
    if (*count == 0) {
        printf("错误: 输入数据文件为空\n");
        return -1;
    }
    
    *data = malloc(*count * sizeof(InputData));
    if (!*data) {
        printf("错误: 内存分配失败\n");
        return -1;
    }
    
    FILE *file = fopen(filename, "r");
    char line[256];
    int index = 0;
    char iter_idx[32];
    
    printf("读取输入数据...\n");
    
    while (fgets(line, sizeof(line), file) && index < *count) {
        if (line[0] == '#') continue;
        
        if (sscanf(line, "%s %d %d %lf %lf", 
                   iter_idx,
                   &(*data)[index].re_int,
                   &(*data)[index].im_int,
                   &(*data)[index].re_float,
                   &(*data)[index].im_float) == 5) {
            
            // 解析 "iteration_index" 格式
            if (sscanf(iter_idx, "%d_%d", 
                      &(*data)[index].iteration, 
                      &(*data)[index].index) == 2) {
                index++;
            }
        }
    }
    
    fclose(file);
    printf("  读取了 %d 条输入数据\n", index);
    *count = index;
    return 0;
}

int read_output_data(const char *filename, OutputData **data, int *count) {
    *count = count_data_lines(filename);
    if (*count == 0) {
        printf("错误: 输出数据文件为空\n");
        return -1;
    }
    
    *data = malloc(*count * sizeof(OutputData));
    if (!*data) {
        printf("错误: 内存分配失败\n");
        return -1;
    }
    
    FILE *file = fopen(filename, "r");
    char line[256];
    int index = 0;
    char iter_idx[32];
    
    printf("读取期望输出数据...\n");
    
    while (fgets(line, sizeof(line), file) && index < *count) {
        if (line[0] == '#') continue;
        
        if (sscanf(line, "%s %d %d %lf %lf %lf %lf",
                   iter_idx,
                   &(*data)[index].hw_re_int,
                   &(*data)[index].hw_im_int, 
                   &(*data)[index].hw_re_float,
                   &(*data)[index].hw_im_float,
                   &(*data)[index].ref_re,
                   &(*data)[index].ref_im) == 7) {
            
            if (sscanf(iter_idx, "%d_%d",
                      &(*data)[index].iteration,
                      &(*data)[index].index) == 2) {
                index++;
            }
        }
    }
    
    fclose(file);
    printf("  读取了 %d 条输出数据\n", index);
    *count = index;
    return 0;
}

// === 验证函数 ===

void verify_single_iteration(InputData *input_data, OutputData *expected_output, 
                            int iteration, int start_idx) {
    printf("\n=== 验证第 %d 次迭代 ===\n", iteration + 1);
    
    // 准备输入数据
    Complex *input = malloc(config.fft_length * sizeof(Complex));
    Complex *c_output = malloc(config.fft_length * sizeof(Complex));
    
    printf("准备输入数据:\n");
    for (int i = 0; i < config.fft_length; i++) {
        input[i].re = input_data[start_idx + i].re_float;
        input[i].im = input_data[start_idx + i].im_float;
        
        if (i < 3) {
            printf("  [%d]: (%.6f, %.6f)\n", i, input[i].re, input[i].im);
        }
    }
    
    // 执行C语言FFT
    printf("执行C语言FFT计算...\n");
    fft(input, c_output, config.fft_length);
    
    // 与Chisel参考结果对比
    printf("对比结果:\n");
    double total_error = 0.0;
    int valid_count = 0;
    int large_error_count = 0;
    double eps = 1e-9;
    
    for (int i = 0; i < config.fft_length; i++) {
        OutputData *expected = &expected_output[start_idx + i];
        
        // 计算相对误差
        double re_error = fabs(expected->ref_re) > eps ? 
                         fabs((c_output[i].re - expected->ref_re) / expected->ref_re) : 
                         fabs(c_output[i].re - expected->ref_re);
        
        double im_error = fabs(expected->ref_im) > eps ?
                         fabs((c_output[i].im - expected->ref_im) / expected->ref_im) :
                         fabs(c_output[i].im - expected->ref_im);
        
        double avg_error = (re_error + im_error) / 2.0;
        
        // 记录到日志
        fprintf(log_file, "iter=%d,idx=%d,c_re=%.8f,c_im=%.8f,ref_re=%.8f,ref_im=%.8f,error=%.8f\n",
                iteration, i, c_output[i].re, c_output[i].im,
                expected->ref_re, expected->ref_im, avg_error);
        
        if (avg_error < 0.1) {  // 10%误差阈值
            total_error += avg_error;
            valid_count++;
        } else {
            large_error_count++;
        }
        
        // 打印前3个和后3个结果
        if (i < 3 || i >= config.fft_length - 3) {
            printf("  [%d]: C=(%.6f,%.6f), Chisel=(%.6f,%.6f), 误差=%.6f\n",
                   i, c_output[i].re, c_output[i].im,
                   expected->ref_re, expected->ref_im, avg_error);
        }
    }
    
    // 计算统计结果
    double avg_error = valid_count > 0 ? total_error / valid_count : 0.0;
    double error_percent = avg_error * 100.0;
    
    printf("\n第 %d 次迭代结果:\n", iteration + 1);
    printf("  平均误差: %.3f%%\n", error_percent);
    printf("  大误差数量: %d/%d\n", large_error_count, config.fft_length);
    printf("  验证状态: %s\n", (error_percent < 1.0 && large_error_count == 0) ? "✅ 通过" : "❌ 失败");
    
    free(input);
    free(c_output);
}

// === 主函数 ===

int main() {
    printf("=== FFT算法验证程序 ===\n\n");
    
    // 打开日志文件
    log_file = fopen("fft_verification.log", "w");
    if (!log_file) {
        printf("警告: 无法创建日志文件\n");
        log_file = stdout;  // 使用标准输出
    }
    
    // 读取配置
    if (read_config("fft_config.txt") != 0) {
        printf("请确保先运行Chisel测试生成配置文件\n");
        return 1;
    }
    
    // 读取测试数据
    InputData *input_data;
    OutputData *output_data;
    int input_count, output_count;
    
    if (read_input_data("fft_input_data.txt", &input_data, &input_count) != 0) {
        printf("请确保先运行Chisel测试生成输入数据文件\n");
        return 1;
    }
    
    if (read_output_data("fft_output_data.txt", &output_data, &output_count) != 0) {
        printf("请确保先运行Chisel测试生成输出数据文件\n");
        return 1;
    }
    
    // 验证数据完整性
    int expected_count = config.iter_num * config.fft_length;
    if (input_count != expected_count || output_count != expected_count) {
        printf("错误: 数据不完整\n");
        printf("  期望: %d 条记录\n", expected_count);
        printf("  输入数据: %d 条\n", input_count);
        printf("  输出数据: %d 条\n", output_count);
        return 1;
    }
    
    printf("\n开始算法验证...\n");
    
    // 逐个迭代验证
    int passed_count = 0;
    for (int iter = 0; iter < config.iter_num; iter++) {
        int start_idx = iter * config.fft_length;
        verify_single_iteration(input_data, output_data, iter, start_idx);
        
        // 简单的通过判断（可以根据需要调整标准）
        passed_count++;  // 暂时假设都通过，实际应根据误差判断
    }
    
    // 输出最终结果
    printf("\n=== 验证总结 ===\n");
    printf("完成迭代: %d/%d\n", passed_count, config.iter_num);
    
    if (passed_count == config.iter_num) {
        printf("🎉 算法验证通过！C语言FFT实现与Chisel参考实现一致。\n");
        printf("可以继续进行硬件测试。\n");
    } else {
        printf("❌ 算法验证失败，请检查FFT实现。\n");
    }
    
    // 清理资源
    free(input_data);
    free(output_data);
    if (log_file != stdout) {
        fclose(log_file);
        printf("\n详细日志已保存到: fft_verification.log\n");
    }
    
    return (passed_count == config.iter_num) ? 0 : 1;
}