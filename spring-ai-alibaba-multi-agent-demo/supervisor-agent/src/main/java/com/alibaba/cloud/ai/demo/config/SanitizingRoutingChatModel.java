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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ============================================
 * 路由输出清洗 ChatModel 包装器
 * ============================================
 *
 * 【为什么需要这个类】
 * LlmRoutingAgent 的核心逻辑是：让 LLM 分析用户意图，输出一个"子 Agent 名称"。
 * 但 LLM 的输出往往包含多余的文字，例如：
 *   "用户想要咨询产品信息，我应该调用 consult_agent 来处理这个请求。"
 * 而 LlmRoutingAgent 实际上只需要 "consult_agent" 这个字符串来做路由决策。
 *
 * 更麻烦的是，有些模型（如 Claude、DeepSeek）会输出  thinking... response 思考块，
 * 这些内容对路由决策没有意义，需要被过滤掉。
 *
 * 【这个类的职责】
 * 1. 过滤掉  thinking... response 标签及其内容（思考块过滤）
 * 2. 从 LLM 输出中提取最后一个有效的子 Agent 名称（路由提取）
 * 3. 如果提取不到路由名称，返回清洗后的原始文本（降级处理）
 *
 * 【设计模式：装饰器模式（Decorator Pattern）】
 * 这个类实现了 ChatModel 接口，内部持有一个 delegate（被装饰的 ChatModel），
 * 在调用 delegate 之后对输出进行清洗处理，对调用者完全透明。
 *
 * 【处理流程】
 * LLM 原始输出 → sanitize()
 *   ├── 第一步：用正则过滤  thinking... response 块
 *   ├── 第二步：在清洗后的文本中找子 Agent 名称
 *   │   ├── 找到了 → 返回子 Agent 名称（如 "consult_agent"）
 *   │   └── 没找到 → 回到原始文本中找
 *   │       ├── 找到了 → 返回子 Agent 名称
 *   │       └── 没找到 → 返回清洗后的文本（降级）
 *
 * 【容错设计】
 * 首先在清洗后的文本中查找路由（withoutThinking），
 * 找不到再回退到原始文本中查找（text）。
 * 这是因为  thinking 块中可能也包含子 Agent 名称，但那是模型的"思考"而非"决策"，
 * 所以优先相信清洗后的文本中的路由决策。
 */
final class SanitizingRoutingChatModel implements ChatModel {

    /**
     * 匹配  thinking... response 思考块的正则表达式
     * (?is) 表示：忽略大小写(i) + 点号匹配换行符(s)，即 DOTALL 模式
     * 匹配从  thinking 到  response 之间的所有内容（包括换行）
     */
    private static final Pattern THINKING_BLOCK = Pattern.compile("(?is) thinking.*? response");

    /**
     * 被装饰的原始 ChatModel（通常是 OpenAI 兼容的 ChatModel）
     */
    private final ChatModel delegate;

    /**
     * 路由匹配正则：匹配子 Agent 名称列表中的任意一个
     * 例如 routeIds = ["consult_agent", "feedback_agent", "order_agent"]
     * 则生成正则：\b(consult_agent|feedback_agent|order_agent)\b
     * \b 是单词边界，确保精确匹配（如 "consult_agent" 不会匹配到 "consult_agent_backup"）
     */
    private final Pattern routePattern;

    /**
     * 构造函数
     * @param delegate 被包装的原始 ChatModel
     * @param routeIds 有效的子 Agent 名称列表，用于构建路由匹配正则
     */
    SanitizingRoutingChatModel(ChatModel delegate, List<String> routeIds) {
        this.delegate = delegate;
        // 将 routeIds 中的每个名称用 Pattern.quote 转义（防止特殊字符），然后用 | 连接
        this.routePattern = Pattern.compile(
                "\\b(" + String.join("|", routeIds.stream().map(Pattern::quote).toList()) + ")\\b");
    }

    // ============================================
    // ChatModel 接口实现（全部委托给 delegate，然后对输出做清洗）
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

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 流式调用：对每个流式响应块都做清洗
        return delegate.stream(prompt).map(this::sanitize);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    // ============================================
    // 清洗逻辑
    // ============================================

    /**
     * 清洗 ChatResponse：对其中的每个 Generation 做清洗
     */
    private ChatResponse sanitize(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return response;
        }
        List<Generation> generations = response.getResults().stream()
                .map(this::sanitize)
                .toList();
        return new ChatResponse(generations, response.getMetadata());
    }

    /**
     * 清洗 Generation：对其中的 AssistantMessage 文本做清洗
     */
    private Generation sanitize(Generation generation) {
        if (generation == null || generation.getOutput() == null) {
            return generation;
        }
        AssistantMessage output = generation.getOutput();
        AssistantMessage sanitizedOutput = new AssistantMessage(
                sanitize(output.getText()),    // 清洗文本
                output.getMetadata(),           // 保留元数据
                output.getToolCalls(),          // 保留工具调用
                output.getMedia());             // 保留媒体内容
        return new Generation(sanitizedOutput, generation.getMetadata());
    }

    /**
     * 核心清洗方法：过滤思考块 + 提取路由名称
     *
     * @param text LLM 原始输出文本
     * @return 清洗后的文本（优先返回子 Agent 名称，其次返回清洗后的文本）
     */
    private String sanitize(String text) {
        if (text == null) {
            return null;
        }

        // 第一步：去掉  thinking... response 块
        String withoutThinking = THINKING_BLOCK.matcher(text).replaceAll("").trim();

        // 第二步：在清洗后的文本中找最后一个子 Agent 名称
        String routeId = lastRouteId(withoutThinking);
        if (routeId != null) {
            return routeId;  // 找到了，直接返回子 Agent 名称
        }

        // 第三步：回退到原始文本中找（容错处理）
        routeId = lastRouteId(text);
        return routeId != null ? routeId : withoutThinking;  // 找不到就返回清洗后文本
    }

    /**
     * 在文本中查找最后一个匹配的子 Agent 名称
     * 取"最后一个"是因为 LLM 的输出中可能多次提到 Agent 名称，
     * 最后一个通常是最终决策。
     */
    private String lastRouteId(String text) {
        Matcher matcher = routePattern.matcher(text);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }
}