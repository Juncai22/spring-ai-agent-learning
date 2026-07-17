package com.pi.agent.types;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 上下文容器，持有系统提示词（system prompt）、消息历史记录和可用的工具列表。
 *
 * <p>{@code messages} 列表是<b>可变的</b>（{@link ArrayList}），以便 Agent 主循环在
 * 执行过程中追加消息。{@code tools} 列表是可选的，可以为 {@code null} 或空列表。
 *
 * <p>使用 {@link Builder} 构建实例：
 * <pre>{@code
 * AgentContext ctx = AgentContext.builder()
 *     .systemPrompt("你是一个乐于助人的助手。")
 *     .messages(new ArrayList<>(existingMessages))
 *     .tools(myTools)
 *     .build();
 * }</pre>
 *
 * <p><b>验证需求：9.1, 9.2</b>
 */
public class AgentContext {

    /** 系统提示词，用于指导 LLM 的行为 */
    private String systemPrompt;

    /** 消息历史记录列表，Agent 主循环可向其追加消息 */
    private List<AgentMessage> messages;

    /** 可用工具列表，可以为 null 表示没有工具 */
    private List<AgentTool> tools;

    /**
     * 默认构造函数 — 将所有字段初始化为安全的默认值。
     */
    public AgentContext() {
        this.systemPrompt = "";
        this.messages = new ArrayList<>();
        this.tools = null;
    }

    /**
     * 全参构造函数。
     *
     * @param systemPrompt 系统提示词（若为 {@code null} 则默认为空字符串）
     * @param messages     可变的消息历史记录（若为 {@code null} 则默认为空 {@link ArrayList}）
     * @param tools        可用工具列表（可以为 {@code null} 表示没有工具）
     */
    public AgentContext(String systemPrompt, List<AgentMessage> messages, List<AgentTool> tools) {
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        this.tools = tools;
    }

    // --------------- Getter / Setter ---------------

    /**
     * 获取系统提示词。
     *
     * @return 系统提示词字符串
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * 设置系统提示词。
     *
     * @param systemPrompt 系统提示词（若为 {@code null} 则默认为空字符串）
     */
    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
    }

    /**
     * 返回<b>可变的</b>消息列表。调用者可以直接向其追加消息。
     *
     * @return 可变的消息列表
     */
    public List<AgentMessage> getMessages() {
        return messages;
    }

    /**
     * 设置消息列表，内部会复制一份以保证可变性。
     *
     * @param messages 消息列表（若为 {@code null} 则默认为空列表）
     */
    public void setMessages(List<AgentMessage> messages) {
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
    }

    /**
     * 返回工具列表，若未配置工具则返回 {@code null}。
     *
     * @return 工具列表，可能为 null
     */
    public List<AgentTool> getTools() {
        return tools;
    }

    /**
     * 设置工具列表。
     *
     * @param tools 工具列表，可以为 null 表示没有工具
     */
    public void setTools(List<AgentTool> tools) {
        this.tools = tools;
    }

    // --------------- Builder ---------------

    /**
     * 创建一个新的 {@link Builder} 实例。
     *
     * @return 一个新的 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@link AgentContext} 的构建器。遵循与 pi-ai-core 中 {@code Context.Builder} 相同的模式。
     */
    public static final class Builder {

        /** 系统提示词，默认为空字符串 */
        private String systemPrompt = "";

        /** 消息列表 */
        private List<AgentMessage> messages;

        /** 工具列表 */
        private List<AgentTool> tools;

        private Builder() {
        }

        /**
         * 设置系统提示词。
         *
         * @param systemPrompt 系统提示词（若为 {@code null} 则默认为空字符串）
         * @return 当前 Builder 实例
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * 设置消息列表。提供的列表会<b>复制</b>到一个新的 {@link ArrayList} 中以保证可变性。
         *
         * @param messages 初始消息列表
         * @return 当前 Builder 实例
         */
        public Builder messages(List<AgentMessage> messages) {
            this.messages = messages;
            return this;
        }

        /**
         * 设置工具列表（可选 — 可以为 {@code null}）。
         *
         * @param tools 可用工具列表
         * @return 当前 Builder 实例
         */
        public Builder tools(List<AgentTool> tools) {
            this.tools = tools;
            return this;
        }

        /**
         * 构建 {@link AgentContext} 实例。
         *
         * @return 一个新的 AgentContext 实例，消息列表为可变副本
         */
        public AgentContext build() {
            AgentContext ctx = new AgentContext();
            ctx.systemPrompt = this.systemPrompt != null ? this.systemPrompt : "";
            ctx.messages = this.messages != null ? new ArrayList<>(this.messages) : new ArrayList<>();
            ctx.tools = this.tools;
            return ctx;
        }
    }
}