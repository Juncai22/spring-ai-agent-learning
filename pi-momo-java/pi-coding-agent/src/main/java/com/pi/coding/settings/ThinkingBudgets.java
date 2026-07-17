package com.pi.coding.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 各思考级别（Thinking Level）的 Token 预算设置。
 *
 * <p>当 AI 模型支持"思考"（Thinking）功能时，系统会分配额外的 Token 用于模型的
 * 内部推理过程。不同的思考级别对应不同的 Token 预算，级别越高，模型能进行
 * 更深入的推理，但消耗的 Token 也越多。
 *
 * <p>思考级别说明：
 * <ul>
 *   <li><b>low</b> — 轻度思考（默认预算：2000 Token），适用于简单问题</li>
 *   <li><b>medium</b> — 中度思考（默认预算：8000 Token），适用于一般复杂问题</li>
 *   <li><b>high</b> — 深度思考（默认预算：16000 Token），适用于复杂推理问题</li>
 *   <li><b>xhigh</b> — 极深度思考（默认预算：32000 Token），适用于需要
 *       长时间链式推理的极端复杂问题</li>
 * </ul>
 *
 * <p>实际使用中，思考级别通过 {@link SettingsManager#getDefaultThinkingLevel()} 设定，
 * 系统根据该级别选择对应的 Token 预算值。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThinkingBudgets(
    @JsonProperty("low") Integer low,
    @JsonProperty("medium") Integer medium,
    @JsonProperty("high") Integer high,
    @JsonProperty("xhigh") Integer xhigh
) {
    /** 默认思考预算：low=2000, medium=8000, high=16000, xhigh=32000 */
    public static final ThinkingBudgets DEFAULT = new ThinkingBudgets(2000, 8000, 16000, 32000);

    /** 获取轻度思考的 Token 预算，未配置时默认返回 2000 */
    public int getLow() { return low != null ? low : 2000; }
    /** 获取中度思考的 Token 预算，未配置时默认返回 8000 */
    public int getMedium() { return medium != null ? medium : 8000; }
    /** 获取深度思考的 Token 预算，未配置时默认返回 16000 */
    public int getHigh() { return high != null ? high : 16000; }
    /** 获取极深度思考的 Token 预算，未配置时默认返回 32000 */
    public int getXhigh() { return xhigh != null ? xhigh : 32000; }
}
