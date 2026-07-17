package com.pi.agent.types;

import com.pi.ai.core.types.ThinkingLevel;

/**
 * Agent 层思考级别枚举，扩展 pi-ai-core 的 {@link ThinkingLevel}，
 * 增加了 {@link #OFF} 选项以完全禁用推理能力。
 *
 * <p>当设置为 {@code OFF} 时，Agent 在循环配置中将推理参数设为 {@code null}。
 * 其他所有值直接映射到对应的 {@link ThinkingLevel} 常量。
 */
public enum AgentThinkingLevel {

    /** 完全禁用推理 — 映射为 {@code null}（无推理）。 */
    OFF,

    /** 映射到 {@link ThinkingLevel#MINIMAL}。 */
    MINIMAL,

    /** 映射到 {@link ThinkingLevel#LOW}。 */
    LOW,

    /** 映射到 {@link ThinkingLevel#MEDIUM}。 */
    MEDIUM,

    /** 映射到 {@link ThinkingLevel#HIGH}。 */
    HIGH,

    /** 映射到 {@link ThinkingLevel#XHIGH}。 */
    XHIGH;

    /**
     * 将此 Agent 层思考级别转换为 pi-ai-core 的 {@link ThinkingLevel}。
     *
     * @return 对应的 {@link ThinkingLevel}，若为 {@link #OFF} 则返回 {@code null}
     */
    public ThinkingLevel toPiAiThinkingLevel() {
        if (this == OFF) {
            return null;
        }
        return ThinkingLevel.valueOf(this.name());
    }
}