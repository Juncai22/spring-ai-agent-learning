package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Thinking/reasoning effort level for LLM calls.
 * LLM 调用时的思考/推理力度级别枚举，控制模型在回答前进行深入思考的程度。
 *
 * <ul>
 *   <li>{@link #MINIMAL} — 最小思考，适用于简单问题</li>
 *   <li>{@link #LOW} — 低力度思考</li>
 *   <li>{@link #MEDIUM} — 中等力度思考</li>
 *   <li>{@link #HIGH} — 高力度思考，适用于复杂推理</li>
 *   <li>{@link #XHIGH} — 极高力度思考，适用于最复杂的推理任务</li>
 * </ul>
 */
// 枚举：思考/推理力度级别
// 控制 LLM 在生成最终回答前进行内部推理的深度
// 力度越高，模型会花更多 Token 进行推理，回答质量可能更高但延迟和成本也更高
public enum ThinkingLevel {

    // 最小思考：基本不做额外推理，适用于简单问题（如问答、翻译）
    @JsonProperty("minimal")
    MINIMAL,

    // 低力度思考：轻度推理，适用于日常问题
    @JsonProperty("low")
    LOW,

    // 中等力度思考：默认推理级别，适用于大多数场景
    @JsonProperty("medium")
    MEDIUM,

    // 高力度思考：深入推理，适用于复杂问题（如数学、编程）
    @JsonProperty("high")
    HIGH,

    // 极高力度思考：极致推理，适用于最复杂的分析任务
    @JsonProperty("xhigh")
    XHIGH
}