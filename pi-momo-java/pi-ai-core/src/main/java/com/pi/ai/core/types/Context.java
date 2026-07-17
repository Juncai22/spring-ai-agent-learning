package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 一次 LLM 调用的完整上下文，包含系统提示、消息列表和工具列表。
 * 作为发送给 LLM 的完整请求上下文，组装了对话所需的所有信息。
 *
 * <p>{@code systemPrompt} 和 {@code tools} 为可选字段（nullable），
 * 序列化时 null 值会被省略（{@code NON_NULL}）。
 *
 * @param systemPrompt 系统提示（可选），用于设定 AI 的行为和角色
 * @param messages     消息列表，按时间顺序排列的对话消息
 * @param tools        工具列表（可选），向 LLM 注册的可调用工具
 */
// 序列化时忽略值为 null 的字段
@JsonInclude(JsonInclude.Include.NON_NULL)
// 使用 Java record 定义不可变的 LLM 调用上下文
public record Context(
    // 系统提示：设定 AI 的角色、行为规则和输出格式（可选）
    @JsonProperty("systemPrompt") String systemPrompt,
    // 消息列表：按时间顺序排列的对话历史，包含用户消息、助手消息、工具结果消息
    @JsonProperty("messages") List<Message> messages,
    // 工具列表：向 LLM 注册的可调用工具定义（可选）
    @JsonProperty("tools") List<Tool> tools
) {

    /**
     * 仅包含消息列表的便捷构造方法。
     *
     * @param messages 消息列表
     */
    // Step 1: 便捷构造方法，仅传入消息列表
    // 原因：当不需要系统提示和工具时，可以使用更简洁的构造器
    public Context(List<Message> messages) {
        // Step 2: 委托给主构造器，systemPrompt 和 tools 均为 null
        this(null, messages, null);
    }

    /**
     * 包含系统提示和消息列表的便捷构造方法。
     *
     * @param systemPrompt 系统提示
     * @param messages     消息列表
     */
    // Step 1: 便捷构造方法，传入系统提示和消息列表
    public Context(String systemPrompt, List<Message> messages) {
        // Step 2: 委托给主构造器，tools 为 null
        this(systemPrompt, messages, null);
    }

    /**
     * 创建包含所有字段的 Context 的静态工厂方法。
     *
     * @param systemPrompt 系统提示（可选）
     * @param messages     消息列表
     * @param tools        工具列表（可选）
     * @return 新的 Context 实例
     */
    // Step 1: 静态工厂方法，包含所有字段
    // 原因：使用静态工厂方法可以提供更好的语义（of 表示"由...组成"）
    public static Context of(String systemPrompt, List<Message> messages, List<Tool> tools) {
        return new Context(systemPrompt, messages, tools);
    }

    /**
     * 创建仅包含消息列表的 Context 的静态工厂方法。
     *
     * @param messages 消息列表
     * @return 新的 Context 实例
     */
    // Step 1: 静态工厂方法，仅需消息列表
    public static Context of(List<Message> messages) {
        return new Context(null, messages, null);
    }

    /**
     * 创建 Context.Builder 实例。
     *
     * @return 新的 Builder
     */
    // 静态工厂方法，返回 Builder 实例
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Context 的 Builder 模式实现，支持链式调用构建 Context。
     */
    // Builder 内部类：提供链式调用构建 Context
    public static final class Builder {
        // 系统提示（可选，初始为 null）
        private String systemPrompt;
        // 消息列表（初始为空列表，防止 NPE）
        private List<Message> messages = List.of();
        // 工具列表（可选，初始为 null）
        private List<Tool> tools;

        // 私有构造器，防止外部直接实例化
        private Builder() { }

        /** 设置系统提示 */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** 设置消息列表 */
        public Builder messages(List<Message> messages) {
            this.messages = messages;
            return this;
        }

        /** 设置工具列表 */
        public Builder tools(List<Tool> tools) {
            this.tools = tools;
            return this;
        }

        /** 构建 Context 实例 */
        public Context build() {
            return new Context(systemPrompt, messages, tools);
        }
    }
}