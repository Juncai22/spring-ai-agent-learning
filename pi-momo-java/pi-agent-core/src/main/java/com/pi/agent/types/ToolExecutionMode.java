package com.pi.agent.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 配置来自同一条助手消息中的多个工具调用如何执行。
 *
 * <ul>
 *   <li>{@link #SEQUENTIAL} — 每个工具调用按顺序准备、执行和完成，完成后才开始下一个。</li>
 *   <li>{@link #PARALLEL} — 工具调用按顺序准备，然后并发执行。最终结果按原始调用顺序发出。</li>
 * </ul>
 */
public enum ToolExecutionMode {

    /**
     * 顺序执行：每个工具调用依次准备、执行、完成，
     * 完成后才开始下一个工具调用。
     */
    @JsonProperty("sequential")
    SEQUENTIAL,

    /**
     * 并行执行：工具调用按顺序准备，然后并发执行。
     * 最终结果按照原始调用顺序发出。
     */
    @JsonProperty("parallel")
    PARALLEL
}