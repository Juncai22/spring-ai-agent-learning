package com.pi.ai.core.types;

/**
 * Token usage statistics for a single LLM call.
 * 单次 LLM 调用的 Token 用量统计，包含输入、输出、缓存等维度的计数和费用明细。
 *
 * <p>Includes input/output/cache token counts and associated cost breakdown.
 * This record is immutable; use {@link MutableUsage} for streaming accumulation,
 * then convert via {@link MutableUsage#toUsage()}.
 * 包含输入/输出/缓存的 Token 计数和对应的费用明细。
 * 该 record 是不可变的；流式累积请使用 {@link MutableUsage}，
 * 流结束后通过 {@link MutableUsage#toUsage()} 转换为不可变实例。
 */
// 使用 Java record 定义不可变的 Token 用量统计
// 所有字段都是 final 的，一旦创建不可修改
public record Usage(
        // 输入 Token 数：请求中发送给 LLM 的 Token 总量
        int input,
        // 输出 Token 数：LLM 响应中生成的 Token 总量
        int output,
        // 缓存读取 Token 数：从提示缓存中命中的 Token 量
        int cacheRead,
        // 缓存写入 Token 数：写入提示缓存的 Token 量
        int cacheWrite,
        // 总 Token 数：所有 Token 类别的总和
        int totalTokens,
        // 费用明细：按输入、输出、缓存维度拆分的费用
        Cost cost
) {

    /**
     * Cost breakdown for a single LLM call (in dollars).
     * 单次 LLM 调用的费用明细（单位为美元），按输入、输出、缓存读取、缓存写入维度拆分。
     */
    // 内部嵌套 record：费用明细，以美元为单位
    public record Cost(
            // 输入 Token 费用（美元）
            double input,
            // 输出 Token 费用（美元）
            double output,
            // 缓存读取 Token 费用（美元），通常比输入费用低
            double cacheRead,
            // 缓存写入 Token 费用（美元），通常比输入费用低
            double cacheWrite,
            // 总费用（美元）：所有维度费用的总和
            double total
    ) { }
}