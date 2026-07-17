package com.alibaba.cloud.ai.demo.config;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.regex.Pattern;

/**
 * ============================================
 * MiniMax 兼容 ChatModel 包装器
 * ============================================
 *
 * 【为什么需要这个类】
 * 某些 LLM 模型（如 MiniMax-M3、Claude、DeepSeek）会在输出中包含  thinking... response 思考块。
 * 这些思考块是模型的内部推理过程，对终端用户没有意义，需要过滤掉。
 *
 * 【与 SanitizingRoutingChatModel 的区别】
 * | 维度             | SanitizingRoutingChatModel       | MinimaxCompatibleChatModel      |
 * |-----------------|----------------------------------|---------------------------------|
 * | 位置             | supervisor-agent                 | 三个子 Agent 中                 |
 * | 用途             | 路由决策提取（提取 agent name）    | 纯文本清洗（过滤思考块）         |
 * | 流式处理         | 简单 map 变换                     | 有状态流式处理（ThinkingState） |
 * | 输出             | 提取 agent name 或清洗后文本     | 只返回清洗后的文本              |
 *
 * 【流式处理的特殊处理】
 * 流式场景下， thinking 和  response 标签可能跨多个 chunk 出现。
 * 例如：
 *   chunk 1: "让我们来分析一下...  thinking"
 *   chunk 2: "用户想要点奶茶，应该调用 order_agent"
 *   chunk 3: " response 好的，我来帮您下单..."
 *
 * 使用 ThinkingState 跟踪当前是否在思考块内部，跨 chunk 过滤。
 * 这是同步调用不需要的额外处理（同步调用拿到的是完整文本）。
 *
 * 【设计模式：装饰器模式】
 * 与 SanitizingRoutingChatModel 一样，实现 ChatModel 接口，内部持有 delegate。
 */
final class MinimaxCompatibleChatModel implements ChatModel {

    /** 匹配  thinking... response 思考块的正则 */
    private static final Pattern THINKING_BLOCK = Pattern.compile("(?is) thinking.*? response");

    /** 被装饰的原始 ChatModel */
    private final ChatModel delegate;

    MinimaxCompatibleChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    // ============================================
    // 同步调用（简单正则替换）
    // ============================================

    @Override
    public String call(String message) {
        return sanitize(delegate.call(message));
    }

    @Override
    public String call(Message... messages) {
        return sanitize(delegate.call(messages));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return sanitize(delegate.call(prompt));
    }

    // ============================================
    // 流式调用（有状态过滤）
    // ============================================

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            ThinkingState state = new ThinkingState();  // 创建流式状态跟踪器
            return delegate.stream(prompt).handle((response, sink) -> {
                ChatResponse sanitized = sanitizeStreaming(response, state);
                // 只有在有实际文本或工具调用时才发送
                if (hasText(sanitized) || hasToolCalls(sanitized)) {
                    sink.next(sanitized);
                }
            });
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    // ============================================
    // 清洗逻辑
    // ============================================

    private ChatResponse sanitize(ChatResponse response) {
        if (response == null || response.getResults() == null) return response;
        List<Generation> generations = response.getResults().stream()
                .map(generation -> sanitize(generation, this::sanitize))
                .toList();
        return new ChatResponse(generations, response.getMetadata());
    }

    private ChatResponse sanitizeStreaming(ChatResponse response, ThinkingState state) {
        if (response == null || response.getResults() == null) return response;
        List<Generation> generations = response.getResults().stream()
                .map(generation -> sanitize(generation, text -> sanitizeStreaming(text, state)))
                .toList();
        return new ChatResponse(generations, response.getMetadata());
    }

    private Generation sanitize(Generation generation, TextSanitizer sanitizer) {
        if (generation == null || generation.getOutput() == null) return generation;
        AssistantMessage output = generation.getOutput();
        AssistantMessage sanitizedOutput = new AssistantMessage(
                sanitizer.apply(output.getText()),
                output.getMetadata(),
                output.getToolCalls(),
                output.getMedia());
        return new Generation(sanitizedOutput, generation.getMetadata());
    }

    private boolean hasText(ChatResponse response) {
        return response != null && response.getResult() != null
                && response.getResult().getOutput() != null
                && response.getResult().getOutput().getText() != null
                && !response.getResult().getOutput().getText().isEmpty();
    }

    private boolean hasToolCalls(ChatResponse response) {
        return response != null && response.getResult() != null
                && response.getResult().getOutput() != null
                && response.getResult().getOutput().hasToolCalls();
    }

    /** 同步调用：简单正则替换 */
    private String sanitize(String text) {
        return text == null ? "" : THINKING_BLOCK.matcher(text).replaceAll("").trim();
    }

    /**
     * 流式调用：有状态过滤
     *
     * 因为  thinking 和  response 可能跨多个 chunk，
     * 需要用 ThinkingState 跟踪当前是否在思考块内部。
     *
     * 例如：
     *   chunk1: "正常文本  thinking 内部思考"
     *           → 输出 "正常文本 "，设置 inThinking=true
     *   chunk2: "更多思考  response 正常文本"
     *           → 发现  response，设置 inThinking=false，输出 "正常文本"
     */
    private String sanitizeStreaming(String text, ThinkingState state) {
        if (text == null) return "";
        StringBuilder visible = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            if (state.inThinking) {
                // 在思考块内：寻找  response 结束标记
                int end = text.indexOf(" response", index);
                if (end < 0) return visible.toString();  // 还没找到结束标记，整个 chunk 都跳过
                state.inThinking = false;
                index = end + " response".length();
            } else {
                // 在思考块外：寻找  thinking 开始标记
                int start = text.indexOf(" thinking", index);
                if (start < 0) {
                    visible.append(text.substring(index));  // 没有思考块，全部保留
                    break;
                }
                visible.append(text, index, start);  // 保留 thinking 之前的内容
                state.inThinking = true;
                index = start + " thinking".length();
            }
        }
        return visible.toString();
    }

    private interface TextSanitizer {
        String apply(String text);
    }

    /** 流式思考块状态跟踪器 */
    private static final class ThinkingState {
        private boolean inThinking;  // 当前是否在  thinking... 块内部
    }
}