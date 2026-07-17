package com.pi.agent.types;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pi.ai.core.types.Model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Agent 运行时状态，包含配置信息、对话数据和实时执行状态。
 *
 * <p>线程安全策略（依据设计文档）：
 * <ul>
 *   <li>{@code isStreaming} — {@code volatile} 关键字，保证跨线程可见性</li>
 *   <li>{@code streamMessage} — {@code volatile} 关键字，保证跨线程可见性</li>
 *   <li>{@code error} — {@code volatile} 关键字，保证跨线程可见性</li>
 *   <li>{@code pendingToolCalls} — {@link CopyOnWriteArraySet}，支持并发增删操作</li>
 *   <li>{@code messages} — 普通 {@link ArrayList}，仅由 Agent 线程修改</li>
 * </ul>
 *
 * <p>支持 Jackson 序列化，用于调试和日志记录。
 * {@code tools} 字段排除在序列化之外，因为 {@link AgentTool} 是接口，无法进行泛型反序列化。
 *
 * <p><b>验证需求：10.1, 10.2, 10.3, 10.4, 10.5, 39.4</b>
 *
 * @see AgentThinkingLevel
 * @see AgentTool
 * @see AgentMessage
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentState {

    @JsonProperty("systemPrompt")
    private String systemPrompt;

    @JsonProperty("model")
    private Model model;

    @JsonProperty("thinkingLevel")
    private AgentThinkingLevel thinkingLevel;

    /**
     * 工具列表 — 排除在 Jackson 序列化之外，因为 {@link AgentTool}
     * 是接口，无法进行泛型反序列化。
     */
    @JsonIgnore
    private List<AgentTool> tools;

    @JsonProperty("messages")
    private List<AgentMessage> messages;

    /** 使用 volatile 保证跨线程可见性（需求 39.4）。 */
    @JsonProperty("isStreaming")
    private volatile boolean isStreaming;

    /** 使用 volatile 保证跨线程可见性。 */
    @JsonProperty("streamMessage")
    private volatile AgentMessage streamMessage;

    /**
     * 当前正在执行的工具调用 ID 的线程安全集合（需求 10.4, 10.5）。
     */
    @JsonProperty("pendingToolCalls")
    private Set<String> pendingToolCalls;

    /** 使用 volatile 保证跨线程可见性。 */
    @JsonProperty("error")
    private volatile String error;

    // --------------- 构造函数 ---------------

    /**
     * 默认构造函数 — 将所有字段初始化为安全的默认值。
     *
     * <ul>
     *   <li>{@code systemPrompt} = {@code ""}</li>
     *   <li>{@code model} = {@code null}</li>
     *   <li>{@code thinkingLevel} = {@link AgentThinkingLevel#OFF}</li>
     *   <li>{@code tools} = 空列表</li>
     *   <li>{@code messages} = 空的可变列表</li>
     *   <li>{@code isStreaming} = {@code false}</li>
     *   <li>{@code streamMessage} = {@code null}</li>
     *   <li>{@code pendingToolCalls} = 空的 {@link CopyOnWriteArraySet}</li>
     *   <li>{@code error} = {@code null}</li>
     * </ul>
     */
    public AgentState() {
        this.systemPrompt = "";
        this.model = null;
        this.thinkingLevel = AgentThinkingLevel.OFF;
        this.tools = new ArrayList<>();
        this.messages = new ArrayList<>();
        this.isStreaming = false;
        this.streamMessage = null;
        this.pendingToolCalls = new CopyOnWriteArraySet<>();
        this.error = null;
    }

    // --------------- Getter 方法 ---------------

    /** 获取系统提示词。 */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /** 获取当前使用的模型。 */
    public Model getModel() {
        return model;
    }

    /** 获取当前思考级别。 */
    public AgentThinkingLevel getThinkingLevel() {
        return thinkingLevel;
    }

    /** 获取工具列表。 */
    public List<AgentTool> getTools() {
        return tools;
    }

    /**
     * 返回<b>可变的</b>消息列表。仅 Agent 线程应修改此列表。
     *
     * @return 可变的消息列表
     */
    public List<AgentMessage> getMessages() {
        return messages;
    }

    /** 返回当前是否处于流式输出状态。 */
    public boolean isStreaming() {
        return isStreaming;
    }

    /** 返回当前的流式消息。 */
    public AgentMessage getStreamMessage() {
        return streamMessage;
    }

    /**
     * 返回待处理工具调用 ID 的不可修改视图。
     * 底层的 {@link CopyOnWriteArraySet} 是线程安全的，支持增删操作。
     *
     * @return 待处理工具调用 ID 的不可修改集合
     */
    public Set<String> getPendingToolCalls() {
        return Collections.unmodifiableSet(pendingToolCalls);
    }

    /** 返回错误信息。 */
    public String getError() {
        return error;
    }

    // --------------- Setter 方法 ---------------

    /** 设置系统提示词。 */
    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
    }

    /** 设置当前使用的模型。 */
    public void setModel(Model model) {
        this.model = model;
    }

    /** 设置思考级别，若为 null 则默认为 OFF。 */
    public void setThinkingLevel(AgentThinkingLevel thinkingLevel) {
        this.thinkingLevel = thinkingLevel != null ? thinkingLevel : AgentThinkingLevel.OFF;
    }

    /** 设置工具列表，若为 null 则设为空列表。 */
    public void setTools(List<AgentTool> tools) {
        this.tools = tools != null ? tools : new ArrayList<>();
    }

    /** 设置消息列表，若为 null 则设为空列表。 */
    public void setMessages(List<AgentMessage> messages) {
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
    }

    /** 设置流式输出状态。 */
    public void setIsStreaming(boolean isStreaming) {
        this.isStreaming = isStreaming;
    }

    /** 设置当前的流式消息。 */
    public void setStreamMessage(AgentMessage streamMessage) {
        this.streamMessage = streamMessage;
    }

    /**
     * 替换整个待处理工具调用集合。
     *
     * @param pendingToolCalls 新的工具调用 ID 集合（会复制到一个新的 {@link CopyOnWriteArraySet} 中）
     */
    public void setPendingToolCalls(Set<String> pendingToolCalls) {
        this.pendingToolCalls = pendingToolCalls != null
                ? new CopyOnWriteArraySet<>(pendingToolCalls)
                : new CopyOnWriteArraySet<>();
    }

    /** 设置错误信息。 */
    public void setError(String error) {
        this.error = error;
    }

    // --------------- pendingToolCalls 的便捷方法 ---------------

    /**
     * 向待处理集合中添加一个工具调用 ID（需求 10.4）。
     *
     * @param toolCallId 要添加的工具调用 ID
     */
    public void addPendingToolCall(String toolCallId) {
        this.pendingToolCalls.add(toolCallId);
    }

    /**
     * 从待处理集合中移除一个工具调用 ID（需求 10.5）。
     *
     * @param toolCallId 要移除的工具调用 ID
     */
    public void removePendingToolCall(String toolCallId) {
        this.pendingToolCalls.remove(toolCallId);
    }

    /**
     * 清空所有待处理的工具调用 ID。
     */
    public void clearPendingToolCalls() {
        this.pendingToolCalls.clear();
    }
}