package com.pi.ai.provider.google;

import com.pi.ai.core.types.*;
import com.pi.ai.core.util.PiAiJson;
import com.pi.ai.core.util.SimpleOptions;
import com.pi.ai.core.util.UnicodeSanitizer;
import com.pi.ai.provider.common.MessageTransformer;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Google 系列 Provider 共享的工具类——消息转换、工具转换、模型检测等通用逻辑。
 *
 * <p>本类封装了 Google Gemini、Vertex AI 和 Gemini CLI 三个 Provider 共享的转换逻辑，
 * 包括消息格式转换、工具调用格式转换、模型版本检测、thinking 配置等。采用工具类模式
 * （final 类 + 私有构造器），所有方法均为静态方法。
 *
 * <h3>功能列表</h3>
 * <ul>
 *   <li><b>消息转换</b>：{@link #convertMessages(Model, Context)} 将内部消息模型转换为
 *       Google Gemini Content[] 格式（contents 数组 + parts 数组结构）</li>
 *   <li><b>工具转换</b>：{@link #convertTools(List, boolean)} 将 SDK Tool 列表转换为
 *       Gemini functionDeclarations 格式</li>
 *   <li><b>Thinking 处理</b>：{@link #isThinkingPart(Boolean)}、{@link #retainThoughtSignature(String, String)}、
 *       {@link #resolveThoughtSignature(boolean, String)} 等 thinking 相关方法</li>
 *   <li><b>模型检测</b>：{@link #isGemini3ProModel(String)}、{@link #isGemini3FlashModel(String)}、
 *       {@link #isGemini3Model(String)} 判断模型版本</li>
 *   <li><b>Thinking 配置</b>：{@link #getGemini3ThinkingLevel(ThinkingLevel, String)}、
 *       {@link #getGoogleBudget(String, ThinkingLevel, ThinkingBudgets)} 计算 thinking 参数</li>
 *   <li><b>StopReason 映射</b>：{@link #mapStopReasonString(String)} 将 Gemini 的 finishReason
 *       映射为内部 StopReason 枚举</li>
 *   <li><b>Thought Signature 验证</b>：{@link #isValidThoughtSignature(String)} 验证 base64 签名</li>
 * </ul>
 *
 * <h3>Gemini 消息格式</h3>
 * <p>Google Gemini API 使用独特的消息格式：
 * <ul>
 *   <li>contents 数组：每个元素代表一轮对话</li>
 *   <li>每个 content 包含 role（user / model）和 parts 数组</li>
 *   <li>parts 可以是 text、inlineData（图片）、functionCall、functionResponse 等</li>
 *   <li>thinking 内容通过 {@code thought: true} 标记区分</li>
 *   <li>thoughtSignature 用于跨轮对话的推理连续性</li>
 *   <li>工具结果合并为连续的 functionResponse 块</li>
 * </ul>
 *
 * <p>对应 TypeScript 实现中的 google-shared.ts。
 *
 * @see GoogleGeminiProvider
 * @see GoogleVertexProvider
 * @see GoogleGeminiCliProvider
 */
public final class GoogleShared {

    private GoogleShared() {}

    // Thought signatures must be base64 for Google APIs (TYPE_BYTES).
    private static final Pattern BASE64_SIGNATURE_PATTERN = Pattern.compile("^[A-Za-z0-9+/]+=*$");

    // Sentinel value that tells the Gemini API to skip thought signature validation.
    private static final String SKIP_THOUGHT_SIGNATURE = "skip_thought_signature_validator";

    /**
     * Google thinking level enum，对应 Google API 的 ThinkingLevel。
     */
    public enum GoogleThinkingLevel {
        THINKING_LEVEL_UNSPECIFIED, MINIMAL, LOW, MEDIUM, HIGH
    }

    /**
     * 判断一个 part 是否为 thinking 内容。
     * thought: true 是 thinking 内容的标志。
     */
    public static boolean isThinkingPart(Boolean thought) {
        return Boolean.TRUE.equals(thought);
    }

    /**
     * 保留 thought signature（流式中某些 delta 可能省略 signature）。
     */
    public static String retainThoughtSignature(String existing, String incoming) {
        if (incoming != null && !incoming.isEmpty()) return incoming;
        return existing;
    }

    /**
     * 验证 thought signature 是否为有效的 base64 格式。
     */
    public static boolean isValidThoughtSignature(String signature) {
        if (signature == null || signature.isEmpty()) return false;
        if (signature.length() % 4 != 0) return false;
        return BASE64_SIGNATURE_PATTERN.matcher(signature).matches();
    }

    /**
     * 仅保留来自相同 provider/model 且有效 base64 的 signature。
     */
    public static String resolveThoughtSignature(boolean isSameProviderAndModel, String signature) {
        return isSameProviderAndModel && isValidThoughtSignature(signature) ? signature : null;
    }

    /**
     * 通过 Google API 的模型是否需要显式 tool call ID。
     */
    public static boolean requiresToolCallId(String modelId) {
        return modelId.startsWith("claude-") || modelId.startsWith("gpt-oss-");
    }

    /**
     * 将 SDK 消息转换为 Google Gemini Content[] 格式。
     *
     * @param model   目标模型
     * @param context 调用上下文
     * @return Gemini contents 列表
     */
    public static List<Map<String, Object>> convertMessages(Model model, Context context) {
        List<Map<String, Object>> contents = new ArrayList<>();

        MessageTransformer.NormalizeToolCallId normalizeToolCallId = (id, m, src) -> {
            if (!requiresToolCallId(model.id())) return id;
            return id.replaceAll("[^a-zA-Z0-9_-]", "_").substring(0, Math.min(id.length(), 64));
        };

        List<Message> transformed = MessageTransformer.transformMessages(
                context.messages(), model, normalizeToolCallId);

        for (int i = 0; i < transformed.size(); i++) {
            Message msg = transformed.get(i);

            if (msg instanceof UserMessage um) {
                Map<String, Object> content = convertUserMessage(um, model);
                if (content != null) contents.add(content);

            } else if (msg instanceof AssistantMessage am) {
                Map<String, Object> content = convertAssistantMessage(am, model);
                if (content != null) contents.add(content);

            } else if (msg instanceof ToolResultMessage trm) {
                convertToolResult(trm, model, contents);
            }
        }

        return contents;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> convertUserMessage(UserMessage um, Model model) {
        Object content = um.content();
        if (content instanceof String text) {
            return Map.of("role", "user",
                    "parts", List.of(Map.of("text", UnicodeSanitizer.sanitizeSurrogates(text))));
        }
        if (content instanceof List<?> blocks) {
            List<Map<String, Object>> parts = new ArrayList<>();
            boolean supportsImage = model.input() != null && model.input().contains("image");
            for (Object block : blocks) {
                if (block instanceof TextContent tc) {
                    parts.add(Map.of("text", UnicodeSanitizer.sanitizeSurrogates(tc.text())));
                } else if (block instanceof ImageContent ic) {
                    if (supportsImage) {
                        parts.add(Map.of("inlineData", Map.of(
                                "mimeType", ic.mimeType(),
                                "data", ic.data())));
                    }
                }
            }
            // 如果不支持图片，过滤掉非文本 parts
            if (!supportsImage) {
                parts.removeIf(p -> !p.containsKey("text"));
            }
            if (parts.isEmpty()) return null;
            return Map.of("role", "user", "parts", parts);
        }
        return null;
    }

    private static Map<String, Object> convertAssistantMessage(AssistantMessage am, Model model) {
        List<Map<String, Object>> parts = new ArrayList<>();
        boolean isSameProviderAndModel = Objects.equals(am.getProvider(), model.provider())
                && Objects.equals(am.getModel(), model.id());
        boolean isGemini3 = model.id().toLowerCase().contains("gemini-3");

        for (AssistantContentBlock block : am.getContent()) {
            if (block instanceof TextContent tc) {
                if (tc.text() == null || tc.text().trim().isEmpty()) continue;
                String thoughtSignature = resolveThoughtSignature(isSameProviderAndModel, tc.textSignature());
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("text", UnicodeSanitizer.sanitizeSurrogates(tc.text()));
                if (thoughtSignature != null) part.put("thoughtSignature", thoughtSignature);
                parts.add(part);

            } else if (block instanceof ThinkingContent tc) {
                if (tc.thinking() == null || tc.thinking().trim().isEmpty()) continue;
                if (isSameProviderAndModel) {
                    String thoughtSignature = resolveThoughtSignature(true, tc.thinkingSignature());
                    Map<String, Object> part = new LinkedHashMap<>();
                    part.put("thought", true);
                    part.put("text", UnicodeSanitizer.sanitizeSurrogates(tc.thinking()));
                    if (thoughtSignature != null) part.put("thoughtSignature", thoughtSignature);
                    parts.add(part);
                } else {
                    parts.add(Map.of("text", UnicodeSanitizer.sanitizeSurrogates(tc.thinking())));
                }

            } else if (block instanceof ToolCall tc) {
                String thoughtSignature = resolveThoughtSignature(isSameProviderAndModel, tc.thoughtSignature());
                String effectiveSignature = thoughtSignature != null ? thoughtSignature
                        : (isGemini3 ? SKIP_THOUGHT_SIGNATURE : null);

                Map<String, Object> functionCall = new LinkedHashMap<>();
                functionCall.put("name", tc.name());
                functionCall.put("args", tc.arguments() != null ? tc.arguments() : Map.of());
                if (requiresToolCallId(model.id())) {
                    functionCall.put("id", tc.id());
                }

                Map<String, Object> part = new LinkedHashMap<>();
                part.put("functionCall", functionCall);
                if (effectiveSignature != null) part.put("thoughtSignature", effectiveSignature);
                parts.add(part);
            }
        }

        if (parts.isEmpty()) return null;
        return Map.of("role", "model", "parts", parts);
    }

    private static void convertToolResult(ToolResultMessage trm, Model model,
                                             List<Map<String, Object>> contents) {
        boolean supportsImage = model.input() != null && model.input().contains("image");
        boolean supportsMultimodalFunctionResponse = model.id().contains("gemini-3");

        // Extract text and image content
        List<TextContent> textBlocks = new ArrayList<>();
        List<ImageContent> imageBlocks = new ArrayList<>();
        if (trm.content() != null) {
            for (UserContentBlock block : trm.content()) {
                if (block instanceof TextContent tc) textBlocks.add(tc);
                else if (block instanceof ImageContent ic && supportsImage) imageBlocks.add(ic);
            }
        }

        String textResult = textBlocks.stream()
                .map(tc -> tc.text())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        boolean hasText = !textResult.isEmpty();
        boolean hasImages = !imageBlocks.isEmpty();

        String responseValue = hasText ? UnicodeSanitizer.sanitizeSurrogates(textResult)
                : hasImages ? "(see attached image)" : "";

        // Build functionResponse part
        Map<String, Object> responseContent = new LinkedHashMap<>();
        if (trm.isError()) {
            responseContent.put("error", responseValue);
        } else {
            responseContent.put("output", responseValue);
        }

        Map<String, Object> functionResponse = new LinkedHashMap<>();
        functionResponse.put("name", trm.toolName());
        functionResponse.put("response", responseContent);

        if (hasImages && supportsMultimodalFunctionResponse) {
            List<Map<String, Object>> imageParts = new ArrayList<>();
            for (ImageContent ic : imageBlocks) {
                imageParts.add(Map.of("inlineData", Map.of(
                        "mimeType", ic.mimeType(), "data", ic.data())));
            }
            functionResponse.put("parts", imageParts);
        }

        if (requiresToolCallId(model.id())) {
            functionResponse.put("id", trm.toolCallId());
        }

        Map<String, Object> functionResponsePart = Map.of("functionResponse", functionResponse);

        // Merge consecutive function responses into a single user turn
        if (!contents.isEmpty()) {
            Map<String, Object> lastContent = contents.get(contents.size() - 1);
            if ("user".equals(lastContent.get("role"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> lastParts = (List<Map<String, Object>>) lastContent.get("parts");
                if (lastParts != null && lastParts.stream().anyMatch(p -> p.containsKey("functionResponse"))) {
                    // Merge into existing user turn - need mutable list
                    List<Map<String, Object>> mutableParts = new ArrayList<>(lastParts);
                    mutableParts.add(functionResponsePart);
                    contents.set(contents.size() - 1, Map.of("role", "user", "parts", mutableParts));

                    // Add images for older models
                    if (hasImages && !supportsMultimodalFunctionResponse) {
                        addImageUserMessage(imageBlocks, contents);
                    }
                    return;
                }
            }
        }

        contents.add(Map.of("role", "user", "parts", List.of(functionResponsePart)));

        // For older models, add images in a separate user message
        if (hasImages && !supportsMultimodalFunctionResponse) {
            addImageUserMessage(imageBlocks, contents);
        }
    }

    private static void addImageUserMessage(List<ImageContent> imageBlocks, List<Map<String, Object>> contents) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", "Tool result image:"));
        for (ImageContent ic : imageBlocks) {
            parts.add(Map.of("inlineData", Map.of(
                    "mimeType", ic.mimeType(), "data", ic.data())));
        }
        contents.add(Map.of("role", "user", "parts", parts));
    }

    /**
     * 将 SDK Tool 列表转换为 Gemini functionDeclarations 格式。
     *
     * @param tools         工具列表
     * @param useParameters 是否使用 legacy parameters 字段（Cloud Code Assist + Claude 需要）
     * @return Gemini tools 格式
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> convertTools(List<Tool> tools, boolean useParameters) {
        if (tools == null || tools.isEmpty()) return null;
        List<Map<String, Object>> declarations = new ArrayList<>();
        for (Tool tool : tools) {
            Map<String, Object> decl = new LinkedHashMap<>();
            decl.put("name", tool.name());
            decl.put("description", tool.description());
            if (tool.parameters() != null) {
                Map<String, Object> params = PiAiJson.MAPPER.convertValue(tool.parameters(), Map.class);
                if (useParameters) {
                    decl.put("parameters", params);
                } else {
                    decl.put("parametersJsonSchema", params);
                }
            }
            declarations.add(decl);
        }
        return List.of(Map.of("functionDeclarations", declarations));
    }

    /**
     * 将 Gemini FinishReason 字符串映射为 StopReason。
     */
    public static StopReason mapStopReasonString(String reason) {
        if (reason == null) return StopReason.STOP;
        return switch (reason) {
            case "STOP" -> StopReason.STOP;
            case "MAX_TOKENS" -> StopReason.LENGTH;
            default -> StopReason.ERROR;
        };
    }

    /**
     * 判断是否为 Gemini 3 Pro 模型。
     */
    public static boolean isGemini3ProModel(String modelId) {
        return modelId.toLowerCase().matches(".*gemini-3(?:\\.\\d+)?-pro.*");
    }

    /**
     * 判断是否为 Gemini 3 Flash 模型。
     */
    public static boolean isGemini3FlashModel(String modelId) {
        return modelId.toLowerCase().matches(".*gemini-3(?:\\.\\d+)?-flash.*");
    }

    /**
     * 判断是否为 Gemini 3 模型。
     */
    public static boolean isGemini3Model(String modelId) {
        return isGemini3ProModel(modelId) || isGemini3FlashModel(modelId);
    }

    /**
     * 获取 Gemini 3 模型的 thinking level。
     */
    public static GoogleThinkingLevel getGemini3ThinkingLevel(ThinkingLevel effort, String modelId) {
        ThinkingLevel clamped = (effort == ThinkingLevel.XHIGH) ? ThinkingLevel.HIGH : effort;
        if (isGemini3ProModel(modelId)) {
            return switch (clamped) {
                case MINIMAL, LOW -> GoogleThinkingLevel.LOW;
                case MEDIUM, HIGH -> GoogleThinkingLevel.HIGH;
                default -> GoogleThinkingLevel.HIGH;
            };
        }
        return switch (clamped) {
            case MINIMAL -> GoogleThinkingLevel.MINIMAL;
            case LOW -> GoogleThinkingLevel.LOW;
            case MEDIUM -> GoogleThinkingLevel.MEDIUM;
            case HIGH -> GoogleThinkingLevel.HIGH;
            default -> GoogleThinkingLevel.HIGH;
        };
    }

    /**
     * 获取 Google 模型的 thinking budget。
     */
    public static int getGoogleBudget(String modelId, ThinkingLevel effort,
                                       ThinkingBudgets customBudgets) {
        ThinkingLevel clamped = (effort == ThinkingLevel.XHIGH) ? ThinkingLevel.HIGH : effort;

        if (customBudgets != null) {
            Integer custom = switch (clamped) {
                case MINIMAL -> customBudgets.minimal();
                case LOW -> customBudgets.low();
                case MEDIUM -> customBudgets.medium();
                case HIGH -> customBudgets.high();
                default -> null;
            };
            if (custom != null) return custom;
        }

        if (modelId.contains("2.5-pro")) {
            return switch (clamped) {
                case MINIMAL -> 128;
                case LOW -> 2048;
                case MEDIUM -> 8192;
                case HIGH -> 32768;
                default -> -1;
            };
        }

        if (modelId.contains("2.5-flash")) {
            return switch (clamped) {
                case MINIMAL -> 128;
                case LOW -> 2048;
                case MEDIUM -> 8192;
                case HIGH -> 24576;
                default -> -1;
            };
        }

        return -1;
    }
}
