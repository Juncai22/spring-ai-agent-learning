package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 模型定价信息，单位为 $/百万 token。
 * 定义了模型各维度的单价，用于计算单次调用的费用。
 *
 * @param input      输入 token 单价（$/百万 token）
 * @param output     输出 token 单价（$/百万 token）
 * @param cacheRead  缓存读取 token 单价（$/百万 token），通常比输入价格低
 * @param cacheWrite 缓存写入 token 单价（$/百万 token），通常比输入价格低
 */
// 使用 Java record 定义不可变的模型定价信息
// 所有价格单位为：美元/百万 Token（$/1M tokens）
public record ModelCost(
    // 输入 Token 单价：每百万输入 Token 的费用
    @JsonProperty("input") double input,
    // 输出 Token 单价：每百万输出 Token 的费用
    // 通常输出价格高于输入价格
    @JsonProperty("output") double output,
    // 缓存读取 Token 单价：每百万缓存读取 Token 的费用
    // 通常比输入价格低 50%~90%，作为使用缓存的优惠
    @JsonProperty("cacheRead") double cacheRead,
    // 缓存写入 Token 单价：每百万缓存写入 Token 的费用
    // 通常比输入价格低，但高于缓存读取价格
    @JsonProperty("cacheWrite") double cacheWrite
) { }