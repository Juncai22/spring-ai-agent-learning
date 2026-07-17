package com.pi.ai.provider.common;

import com.pi.ai.core.types.*;

import java.util.*;
import java.util.function.BiFunction;

/**
 * 跨 Provider 消息转换器——将内部统一消息模型转换为目标 Provider 兼容的格式。
 *
 * <p>这是 pi-momo 框架中消息适配的核心组件。不同的 AI 模型 Provider 对消息格式有不同
 * 的要求（例如 Anthropic 要求 content 为 block 数组，OpenAI 要求 content 为单一字符串或
 * part 数组，Google Gemini 要求 parts 格式等）。MessageTransformer 负责将内部统一的消息
 * 模型（{@link Message} 及其子类）转换为目标 Model 兼容的格式。
 *
 * <h3>转换处理的内容</h3>
 * <ul>
 *   <li><b>thinking 块转换</b>：跨模型时将 thinking 块转为 TextContent，redacted thinking 丢弃</li>
 *   <li><b>thinkingSignature 保留/移除</b>：同模型保留 thinkingSignature，跨模型移除</li>
 *   <li><b>ToolCall ID 规范化</b>：通过 {@link NormalizeToolCallId} 回调实现跨模型的 ID 格式转换</li>
 *   <li><b>孤立 ToolCall 处理</b>：如果 ToolCall 没有对应的 ToolResultMessage，插入合成错误
 *       ToolResultMessage（内容为 "No result provided"）</li>
 *   <li><b>过滤异常消息</b>：过滤掉 stopReason 为 ERROR 或 ABORTED 的 AssistantMessage</li>
 *   <li><b>文本签名处理</b>：跨模型时移除 textSignature 字段</li>
 * </ul>
 *
 * <h3>两遍处理算法</h3>
 * <p>转换过程分为两遍：
 * <ol>
 *   <li><b>第一遍</b>：遍历所有消息，转换消息内容块（thinking 块转换、ToolCall ID 规范化）
 *       ，建立原始 ToolCall ID 到规范化 ID 的映射</li>
 *   <li><b>第二遍</b>：遍历第一遍结果，处理孤立 ToolCall（插入合成 ToolResult）、过滤错误消息
 *       ，确保消息序列的完整性</li>
 * </ol>
 *
 * <p>对应 TypeScript 实现中的 {@code transformMessages} 函数。
 *
 * @see Message
 * @see UserMessage
 * @see AssistantMessage
 * @see ToolResultMessage
 * @see ToolCall
 * @see ThinkingContent
 */
public final class MessageTransformer {

    private MessageTransformer() {
        // 工具类，禁止实例化
    }

    /**
     * ToolCall ID 规范化回调函数。
     *
     * <p>这是一个函数式接口，用于在不同 Provider 之间转换 ToolCall ID 的格式。
     * 例如，Anthropic 的 ToolCall ID 允许更长的字符串，而 Mistral 要求 9 位字母数字。
     * 通过此回调，每个 Provider 可以自定义其 ID 规范化策略。
     *
     * @param <TApi> API 类型参数（保持与 TypeScript 签名一致，实际未使用）
     */
    @FunctionalInterface
    public interface NormalizeToolCallId {
        /**
         * 规范化 ToolCall ID。
         *
         * @param id     原始 ToolCall ID
         * @param model  目标模型
         * @param source 来源 AssistantMessage
         * @return 规范化后的 ID
         */
        String normalize(String id, Model model, AssistantMessage source);
    }

    /**
     * 将消息列表转换为目标 Model 兼容的格式。
     *
     * <p>这是消息转换的入口方法，采用两遍处理算法：
     *
     * <p><b>第一遍：消息内容转换</b>
     * <ul>
     *   <li>UserMessage：透传</li>
     *   <li>ToolResultMessage：根据 ID 映射更新 toolCallId</li>
     *   <li>AssistantMessage：转换 thinking 块、移除/保留 signature、规范化 ToolCall ID</li>
     * </ul>
     *
     * <p><b>第二遍：消息序列修复</b>
     * <ul>
     *   <li>为孤立 ToolCall 插入合成错误 ToolResultMessage</li>
     *   <li>过滤 stopReason 为 ERROR/ABORTED 的 AssistantMessage</li>
     *   <li>用户消息会中断之前的工具调用流</li>
     * </ul>
     *
     * @param messages           原始消息列表，来自 {@link Context#messages()}
     * @param model              目标模型，用于判断是否同模型以及获取模型兼容性信息
     * @param normalizeToolCallId ToolCall ID 规范化回调，可为 null。为 null 时不做 ID 规范化
     * @return 转换后的消息列表，可直接用于目标 API 的请求体构建
     */
    public static List<Message> transformMessages(
            List<Message> messages,
            Model model,
            NormalizeToolCallId normalizeToolCallId) {

        // 原始 ToolCall ID → 规范化 ID 的映射
        Map<String, String> toolCallIdMap = new HashMap<>();

        // ===== 第一遍：转换消息内容（thinking 块、ToolCall ID 规范化） =====
        List<Message> transformed = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg instanceof UserMessage) {
                // 用户消息直接透传
                transformed.add(msg);
            } else if (msg instanceof ToolResultMessage trm) {
                // ToolResult 消息：如果有 ID 映射则更新 toolCallId
                String normalizedId = toolCallIdMap.get(trm.toolCallId());
                if (normalizedId != null && !normalizedId.equals(trm.toolCallId())) {
                    transformed.add(new ToolResultMessage(
                            trm.role(), normalizedId, trm.toolName(),
                            trm.content(), trm.details(), trm.isError(), trm.timestamp()));
                } else {
                    transformed.add(trm);
                }
            } else if (msg instanceof AssistantMessage am) {
                boolean isSameModel = Objects.equals(am.getProvider(), model.provider())
                        && Objects.equals(am.getApi(), model.api())
                        && Objects.equals(am.getModel(), model.id());

                List<AssistantContentBlock> transformedContent = new ArrayList<>();
                for (AssistantContentBlock block : am.getContent()) {
                    transformContentBlock(block, isSameModel, model, am,
                            normalizeToolCallId, toolCallIdMap, transformedContent);
                }

                // 构建转换后的 AssistantMessage
                AssistantMessage copy = AssistantMessage.builder()
                        .content(transformedContent)
                        .api(am.getApi())
                        .provider(am.getProvider())
                        .model(am.getModel())
                        .usage(am.getUsage())
                        .stopReason(am.getStopReason())
                        .errorMessage(am.getErrorMessage())
                        .timestamp(am.getTimestamp())
                        .build();
                transformed.add(copy);
            } else {
                transformed.add(msg);
            }
        }

        // ===== 第二遍：插入合成 ToolResult、过滤错误消息 =====
        List<Message> result = new ArrayList<>();
        List<ToolCall> pendingToolCalls = new ArrayList<>();
        Set<String> existingToolResultIds = new HashSet<>();

        for (Message msg : transformed) {
            if (msg instanceof AssistantMessage am) {
                // 如果有上一轮的孤立 ToolCall，先插入合成 ToolResult
                insertSyntheticToolResults(pendingToolCalls, existingToolResultIds, result);
                pendingToolCalls.clear();
                existingToolResultIds.clear();

                // 过滤 error/aborted 的 AssistantMessage
                if (am.getStopReason() == StopReason.ERROR
                        || am.getStopReason() == StopReason.ABORTED) {
                    continue;
                }

                // 收集本轮 ToolCall
                if (am.getContent() != null) {
                    for (AssistantContentBlock block : am.getContent()) {
                        if (block instanceof ToolCall tc) {
                            pendingToolCalls.add(tc);
                        }
                    }
                }

                result.add(am);
            } else if (msg instanceof ToolResultMessage trm) {
                existingToolResultIds.add(trm.toolCallId());
                result.add(trm);
            } else if (msg instanceof UserMessage) {
                // 用户消息中断工具流，插入合成 ToolResult
                insertSyntheticToolResults(pendingToolCalls, existingToolResultIds, result);
                pendingToolCalls.clear();
                existingToolResultIds.clear();
                result.add(msg);
            } else {
                result.add(msg);
            }
        }

        return result;
    }

    /**
     * 转换单个内容块。
     *
     * <p>根据是否同模型决定如何处理：
     * <ul>
     *   <li><b>ThinkingContent</b>：redacted 块仅在同模型保留；有 thinkingSignature 的块保留；
     *       空 thinking 块跳过；跨模型时转为 TextContent</li>
     *   <li><b>TextContent</b>：同模型保留 textSignature，跨模型移除</li>
     *   <li><b>ToolCall</b>：跨模型时移除 thoughtSignature；如有 normalizeToolCallId 回调，规范化 ID</li>
     * </ul>
     *
     * @param block               待转换的内容块
     * @param isSameModel         是否与目标模型相同（同 Provider + 同 API + 同模型 ID）
     * @param model               目标模型
     * @param source              来源 AssistantMessage
     * @param normalizeToolCallId ToolCall ID 规范化回调
     * @param toolCallIdMap       原始 ID → 规范化 ID 的映射
     * @param output              输出列表，转换后的内容块添加到此处
     */
    private static void transformContentBlock(
            AssistantContentBlock block,
            boolean isSameModel,
            Model model,
            AssistantMessage source,
            NormalizeToolCallId normalizeToolCallId,
            Map<String, String> toolCallIdMap,
            List<AssistantContentBlock> output) {

        if (block instanceof ThinkingContent tc) {
            // redacted thinking 仅对同模型有效
            if (tc.redacted() != null && tc.redacted()) {
                if (isSameModel) {
                    output.add(tc);
                }
                // 跨模型丢弃 redacted thinking
                return;
            }
            // 同模型且有 thinkingSignature：保留（用于多轮推理连续性）
            if (isSameModel && tc.thinkingSignature() != null) {
                output.add(tc);
                return;
            }
            // 空 thinking 块跳过
            if (tc.thinking() == null || tc.thinking().trim().isEmpty()) {
                return;
            }
            // 同模型保留
            if (isSameModel) {
                output.add(tc);
                return;
            }
            // 跨模型转为 TextContent
            output.add(new TextContent("text", tc.thinking(), null));

        } else if (block instanceof TextContent tc) {
            if (isSameModel) {
                output.add(tc);
            } else {
                // 跨模型移除 textSignature
                output.add(new TextContent("text", tc.text(), null));
            }

        } else if (block instanceof ToolCall tc) {
            ToolCall normalized = tc;

            // 跨模型移除 thoughtSignature
            if (!isSameModel && tc.thoughtSignature() != null) {
                normalized = new ToolCall(tc.type(), tc.id(), tc.name(),
                        tc.arguments(), null);
            }

            // 跨模型且有 normalizeToolCallId 回调：规范化 ID
            if (!isSameModel && normalizeToolCallId != null) {
                String normalizedId = normalizeToolCallId.normalize(tc.id(), model, source);
                if (!normalizedId.equals(tc.id())) {
                    toolCallIdMap.put(tc.id(), normalizedId);
                    normalized = new ToolCall(normalized.type(), normalizedId,
                            normalized.name(), normalized.arguments(),
                            normalized.thoughtSignature());
                }
            }

            output.add(normalized);
        } else {
            output.add(block);
        }
    }

    /**
     * 为孤立的 ToolCall 插入合成错误 ToolResultMessage。
     *
     * <p>当消息序列中出现 ToolCall 但后续没有对应的 ToolResultMessage 时（例如在消息序列
     * 截断或过滤后），此方法会插入一个内容为 "No result provided" 的合成错误
     * ToolResultMessage，确保消息序列的完整性，避免目标 API 抛出格式错误。
     *
     * @param pendingToolCalls     待处理的孤立 ToolCall 列表
     * @param existingToolResultIds 已有 ToolResult 的 ID 集合
     * @param result               结果消息列表，合成消息将添加到此处
     */
    private static void insertSyntheticToolResults(
            List<ToolCall> pendingToolCalls,
            Set<String> existingToolResultIds,
            List<Message> result) {
        for (ToolCall tc : pendingToolCalls) {
            if (!existingToolResultIds.contains(tc.id())) {
                result.add(new ToolResultMessage(
                        "toolResult",
                        tc.id(),
                        tc.name(),
                        List.of(new TextContent("No result provided")),
                        null,
                        true,
                        System.currentTimeMillis()));
            }
        }
    }
}
