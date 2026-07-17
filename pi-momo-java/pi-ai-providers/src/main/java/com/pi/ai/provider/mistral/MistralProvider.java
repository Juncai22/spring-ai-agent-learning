package com.pi.ai.provider.mistral;

import com.pi.ai.core.event.AssistantMessageEvent;
import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.registry.ApiProvider;
import com.pi.ai.core.registry.ModelRegistry;
import com.pi.ai.core.types.*;
import com.pi.ai.core.util.*;
import com.pi.ai.provider.common.BaseProvider;
import com.pi.ai.provider.common.MessageTransformer;
import com.pi.ai.provider.common.SseParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Mistral Chat API Provider（mistral-conversations）——对接 Mistral AI API 的实现。
 *
 * <p>本类实现了与 Mistral AI Chat Completions API 的集成（{@code /v1/chat/completions} 端点）。
 * Mistral 的 API 在标准 OpenAI 兼容格式的基础上，有自己的扩展特性。
 *
 * <h3>支持的 Mistral 特性</h3>
 * <ul>
 *   <li><b>SSE 流式消息生成</b>：标准 OpenAI 格式的流式响应</li>
 *   <li><b>Thinking</b>：支持 Mistral 的 thinking 内容格式（content 中的 thinking 类型 item）</li>
 *   <li><b>Function Calling</b>：支持工具调用，ToolCall ID 需要规范化为 9 位字母数字</li>
 *   <li><b>Prefix Caching</b>：通过 {@code x-affinity} HTTP 头实现 KV-cache 复用</li>
 *   <li><b>Prompt Mode</b>：支持 reasoning 模式（通过 prompt_mode 参数）</li>
 *   <li><b>多模态</b>：支持图片输入（image_url 格式）</li>
 * </ul>
 *
 * <h3>ToolCall ID 规范化</h3>
 * <p>Mistral API 对 ToolCall ID 有严格的长度限制（9 位字母数字），因此本类实现了
 * {@link MistralToolCallIdNormalizer} 内部类，通过 hash 算法将任意长度的 ID 规范化为
 * 9 位字母数字。同时通过冲突检测确保 ID 的唯一性。
 *
 * <h3>消息格式</h3>
 * <p>使用标准 OpenAI Chat Completions 消息格式，但有以下扩展：
 * <ul>
 *   <li>system 角色作为系统提示</li>
 *   <li>user 角色支持文本和图片（image_url 格式）</li>
 *   <li>assistant 角色支持文本、thinking（特殊 thinking 类型）和 tool_calls</li>
 *   <li>tool 角色用于工具结果返回</li>
 *   <li>thinking 内容使用 content 数组中的 {@code type: "thinking"} 对象</li>
 * </ul>
 *
 * @see <a href="https://docs.mistral.ai/">Mistral AI Documentation</a>
 */
public class MistralProvider extends BaseProvider implements ApiProvider {

    private static final Logger log = LoggerFactory.getLogger(MistralProvider.class);
    private static final String API_ID = "mistral-conversations";
    private static final int MISTRAL_TOOL_CALL_ID_LENGTH = 9;

    @Override
    public String api() {
        return API_ID;
    }

    @Override
    public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
        var stream = AssistantMessageEventStream.create();

        CompletableFuture.runAsync(() -> {
            AssistantMessage output = createInitialOutput(model);
            try {
                String apiKey = resolveApiKey(model, options);
                if (apiKey.isEmpty()) {
                    throw new RuntimeException("No API key for provider: " + model.provider());
                }

                Map<String, Object> payload = buildPayload(model, context, options);
                String baseUrl = model.baseUrl() != null ? model.baseUrl() : "https://api.mistral.ai";
                String url = baseUrl + "/v1/chat/completions";

                Map<String, String> headers = buildHeaders(model, options, apiKey);
                String body = toJson(payload);

                var request = buildPostRequest(url, body, headers);
                HttpResponse<InputStream> response = sendWithRetry(request, options);

                stream.push(new AssistantMessageEvent.Start(output));
                processSseStream(response.body(), stream, output, model);

                CancellationSignal signal = options != null ? options.getSignal() : null;
                if (signal != null && signal.isCancelled()) {
                    throw new RuntimeException("请求已取消");
                }

                stream.push(new AssistantMessageEvent.Done(output.getStopReason(), output));
                stream.end(null);

            } catch (Exception e) {
                CancellationSignal signal = options != null ? options.getSignal() : null;
                emitError(stream, output, e, signal);
            }
        });

        return stream;
    }

    @Override
    public AssistantMessageEventStream streamSimple(Model model, Context context, SimpleStreamOptions options) {
        String apiKey = options != null ? options.getApiKey() : null;
        if (apiKey == null || apiKey.isEmpty()) apiKey = EnvApiKeys.getEnvApiKey(model.provider());
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("No API key for provider: " + model.provider());
        }

        StreamOptions base = SimpleOptions.buildBaseOptions(model, options, apiKey);
        ThinkingLevel reasoning = options != null ? options.getReasoning() : null;
        ThinkingLevel clamped = SimpleOptions.clampReasoning(reasoning);

        Map<String, Object> meta = new LinkedHashMap<>();
        if (base.getMetadata() != null) meta.putAll(base.getMetadata());
        if (model.reasoning() && clamped != null) {
            meta.put("_prompt_mode", "reasoning");
        }

        StreamOptions adapted = StreamOptions.builder()
                .temperature(base.getTemperature())
                .maxTokens(base.getMaxTokens())
                .apiKey(base.getApiKey())
                .cacheRetention(base.getCacheRetention())
                .sessionId(base.getSessionId())
                .headers(base.getHeaders())
                .transport(base.getTransport())
                .maxRetryDelayMs(base.getMaxRetryDelayMs())
                .signal(base.getSignal())
                .metadata(meta)
                .build();
        return stream(model, context, adapted);
    }

    // ========== 内部方法 ==========

    private String resolveApiKey(Model model, StreamOptions options) {
        String apiKey = options != null ? options.getApiKey() : null;
        if (apiKey == null || apiKey.isEmpty()) apiKey = EnvApiKeys.getEnvApiKey(model.provider());
        return apiKey != null ? apiKey : "";
    }

    private Map<String, String> buildHeaders(Model model, StreamOptions options, String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!apiKey.isEmpty()) headers.put("Authorization", "Bearer " + apiKey);
        if (model.headers() != null) headers.putAll(model.headers());
        if (options != null && options.getHeaders() != null) headers.putAll(options.getHeaders());
        // x-affinity for KV-cache reuse (prefix caching)
        if (options != null && options.getSessionId() != null) {
            headers.putIfAbsent("x-affinity", options.getSessionId());
        }
        return headers;
    }

    private Map<String, Object> buildPayload(Model model, Context context, StreamOptions options) {
        // Normalize tool call IDs for Mistral
        MistralToolCallIdNormalizer normalizer = new MistralToolCallIdNormalizer();
        List<Message> transformed = MessageTransformer.transformMessages(
                context.messages(), model, (id, m, src) -> normalizer.normalize(id));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model.id());
        payload.put("stream", true);

        List<Map<String, Object>> messages = toChatMessages(transformed, model);

        // System prompt
        if (context.systemPrompt() != null && !context.systemPrompt().isEmpty()) {
            messages.add(0, Map.of("role", "system",
                    "content", UnicodeSanitizer.sanitizeSurrogates(context.systemPrompt())));
        }
        payload.put("messages", messages);

        // Tools
        if (context.tools() != null && !context.tools().isEmpty()) {
            payload.put("tools", convertTools(context.tools()));
        }

        if (options != null && options.getTemperature() != null) {
            payload.put("temperature", options.getTemperature());
        }
        if (options != null && options.getMaxTokens() != null) {
            payload.put("max_tokens", options.getMaxTokens());
        }

        // Prompt mode (reasoning)
        String promptMode = getPromptMode(options);
        if (promptMode != null) {
            payload.put("prompt_mode", promptMode);
        }

        return payload;
    }

    private List<Map<String, Object>> toChatMessages(List<Message> messages, Model model) {
        boolean supportsImages = model.input() != null && model.input().contains("image");
        List<Map<String, Object>> result = new ArrayList<>();

        for (Message msg : messages) {
            if (msg instanceof UserMessage um) {
                Map<String, Object> m = convertUserMessage(um, supportsImages);
                if (m != null) result.add(m);
            } else if (msg instanceof AssistantMessage am) {
                Map<String, Object> m = convertAssistantMessage(am);
                if (m != null) result.add(m);
            } else if (msg instanceof ToolResultMessage trm) {
                result.add(convertToolResult(trm, supportsImages));
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertUserMessage(UserMessage um, boolean supportsImages) {
        Object content = um.content();
        if (content instanceof String text) {
            return Map.of("role", "user", "content", UnicodeSanitizer.sanitizeSurrogates(text));
        }
        if (content instanceof List<?> blocks) {
            List<Map<String, Object>> parts = new ArrayList<>();
            boolean hadImages = false;
            for (Object block : blocks) {
                if (block instanceof TextContent tc) {
                    parts.add(Map.of("type", "text", "text", UnicodeSanitizer.sanitizeSurrogates(tc.text())));
                } else if (block instanceof ImageContent ic) {
                    hadImages = true;
                    if (supportsImages) {
                        parts.add(Map.of("type", "image_url",
                                "imageUrl", "data:" + ic.mimeType() + ";base64," + ic.data()));
                    }
                }
            }
            if (parts.isEmpty()) {
                if (hadImages && !supportsImages) {
                    return Map.of("role", "user",
                            "content", "(image omitted: model does not support images)");
                }
                return null;
            }
            return Map.of("role", "user", "content", parts);
        }
        return null;
    }

    private Map<String, Object> convertAssistantMessage(AssistantMessage am) {
        List<Map<String, Object>> contentParts = new ArrayList<>();
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (AssistantContentBlock block : am.getContent()) {
            if (block instanceof TextContent tc) {
                if (tc.text() != null && !tc.text().trim().isEmpty()) {
                    contentParts.add(Map.of("type", "text",
                            "text", UnicodeSanitizer.sanitizeSurrogates(tc.text())));
                }
            } else if (block instanceof ThinkingContent tc) {
                if (tc.thinking() != null && !tc.thinking().trim().isEmpty()) {
                    contentParts.add(Map.of("type", "thinking",
                            "thinking", List.of(Map.of("type", "text",
                                    "text", UnicodeSanitizer.sanitizeSurrogates(tc.thinking())))));
                }
            } else if (block instanceof ToolCall tc) {
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", tc.id());
                call.put("type", "function");
                call.put("function", Map.of(
                        "name", tc.name(),
                        "arguments", toJson(tc.arguments() != null ? tc.arguments() : Map.of())));
                toolCalls.add(call);
            }
        }

        if (contentParts.isEmpty() && toolCalls.isEmpty()) return null;

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        if (!contentParts.isEmpty()) msg.put("content", contentParts);
        if (!toolCalls.isEmpty()) msg.put("tool_calls", toolCalls);
        return msg;
    }

    private Map<String, Object> convertToolResult(ToolResultMessage trm, boolean supportsImages) {
        String textResult = "";
        boolean hasImages = false;
        if (trm.content() != null) {
            StringBuilder sb = new StringBuilder();
            for (UserContentBlock block : trm.content()) {
                if (block instanceof TextContent tc) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(UnicodeSanitizer.sanitizeSurrogates(tc.text()));
                } else if (block instanceof ImageContent) {
                    hasImages = true;
                }
            }
            textResult = sb.toString().trim();
        }

        String toolText = buildToolResultText(textResult, hasImages, supportsImages, trm.isError());

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", toolText));

        // Add images if supported
        if (supportsImages && trm.content() != null) {
            for (UserContentBlock block : trm.content()) {
                if (block instanceof ImageContent ic) {
                    content.add(Map.of("type", "image_url",
                            "imageUrl", "data:" + ic.mimeType() + ";base64," + ic.data()));
                }
            }
        }

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "tool");
        msg.put("tool_call_id", trm.toolCallId());
        msg.put("name", trm.toolName());
        msg.put("content", content);
        return msg;
    }

    private String buildToolResultText(String text, boolean hasImages, boolean supportsImages, boolean isError) {
        String errorPrefix = isError ? "[tool error] " : "";
        if (!text.isEmpty()) {
            String imageSuffix = hasImages && !supportsImages
                    ? "\n[tool image omitted: model does not support images]" : "";
            return errorPrefix + text + imageSuffix;
        }
        if (hasImages) {
            return supportsImages
                    ? (isError ? "[tool error] (see attached image)" : "(see attached image)")
                    : (isError ? "[tool error] (image omitted: model does not support images)"
                    : "(image omitted: model does not support images)");
        }
        return isError ? "[tool error] (no tool output)" : "(no tool output)";
    }

    private List<Map<String, Object>> convertTools(List<Tool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Tool tool : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("type", "function");
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            if (tool.parameters() != null) {
                function.put("parameters", PiAiJson.MAPPER.convertValue(tool.parameters(), Map.class));
            }
            function.put("strict", false);
            fn.put("function", function);
            result.add(fn);
        }
        return result;
    }

    // ========== SSE 事件处理 ==========

    @SuppressWarnings("unchecked")
    private void processSseStream(InputStream inputStream, AssistantMessageEventStream stream,
                                   AssistantMessage output, Model model) {
        String currentBlockType = null; // "text" or "thinking"
        Map<String, Integer> toolBlocksByKey = new LinkedHashMap<>();
        Map<Integer, StringBuilder> toolPartialArgs = new LinkedHashMap<>();

        Iterator<SseParser.SseEvent> sseIter = SseParser.parse(inputStream);
        while (sseIter.hasNext()) {
            SseParser.SseEvent sseEvent = sseIter.next();
            if (sseEvent.isDone()) break;
            if (sseEvent.isError()) throw new RuntimeException(sseEvent.data());
            if (sseEvent.data() == null || sseEvent.data().isEmpty()) continue;

            Map<String, Object> chunk;
            try {
                chunk = parseJson(sseEvent.data());
            } catch (Exception e) {
                continue;
            }

            // Usage
            Map<String, Object> usage = (Map<String, Object>) chunk.get("usage");
            if (usage != null) {
                int inputTokens = getInt(usage, "prompt_tokens", 0);
                int outputTokens = getInt(usage, "completion_tokens", 0);
                int totalTokens = getInt(usage, "total_tokens", inputTokens + outputTokens);
                Usage u = new Usage(inputTokens, outputTokens, 0, 0, totalTokens, null);
                Usage.Cost cost = ModelRegistry.calculateCost(model, u);
                output.setUsage(new Usage(inputTokens, outputTokens, 0, 0, totalTokens, cost));
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices == null || choices.isEmpty()) continue;
            Map<String, Object> choice = choices.get(0);

            // Finish reason
            String finishReason = (String) choice.get("finish_reason");
            if (finishReason != null) {
                output.setStopReason(mapStopReason(finishReason));
            }

            Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
            if (delta == null) continue;

            // Content
            Object contentObj = delta.get("content");
            if (contentObj != null) {
                if (contentObj instanceof String text) {
                    String textDelta = UnicodeSanitizer.sanitizeSurrogates(text);
                    if (!"text".equals(currentBlockType)) {
                        finishCurrentBlock(currentBlockType, output, stream);
                        output.getContent().add(new TextContent("text", "", null));
                        stream.push(new AssistantMessageEvent.TextStart(
                                output.getContent().size() - 1, output));
                        currentBlockType = "text";
                    }
                    int idx = output.getContent().size() - 1;
                    TextContent tc = (TextContent) output.getContent().get(idx);
                    output.getContent().set(idx, new TextContent("text", tc.text() + textDelta, tc.textSignature()));
                    stream.push(new AssistantMessageEvent.TextDelta(idx, textDelta, output));
                } else if (contentObj instanceof List<?> items) {
                    for (Object item : items) {
                        if (item instanceof Map<?,?> itemMap) {
                            String itemType = (String) ((Map<String, Object>) itemMap).get("type");
                            if ("thinking".equals(itemType)) {
                                currentBlockType = handleThinkingDelta((Map<String, Object>) itemMap,
                                        output, stream, currentBlockType);
                            } else if ("text".equals(itemType)) {
                                String text = (String) ((Map<String, Object>) itemMap).get("text");
                                if (text != null) {
                                    String textDelta = UnicodeSanitizer.sanitizeSurrogates(text);
                                    if (!"text".equals(currentBlockType)) {
                                        finishCurrentBlock(currentBlockType, output, stream);
                                        output.getContent().add(new TextContent("text", "", null));
                                        stream.push(new AssistantMessageEvent.TextStart(
                                                output.getContent().size() - 1, output));
                                        currentBlockType = "text";
                                    }
                                    int idx = output.getContent().size() - 1;
                                    TextContent tc = (TextContent) output.getContent().get(idx);
                                    output.getContent().set(idx, new TextContent("text",
                                            tc.text() + textDelta, tc.textSignature()));
                                    stream.push(new AssistantMessageEvent.TextDelta(idx, textDelta, output));
                                }
                            }
                        }
                    }
                }
            }

            // Tool calls
            List<Map<String, Object>> toolCallDeltas = (List<Map<String, Object>>) delta.get("tool_calls");
            if (toolCallDeltas != null) {
                for (Map<String, Object> tcDelta : toolCallDeltas) {
                    if (currentBlockType != null) {
                        finishCurrentBlock(currentBlockType, output, stream);
                        currentBlockType = null;
                    }
                    processToolCallDelta(tcDelta, output, stream, toolBlocksByKey, toolPartialArgs);
                }
            }
        }

        // Finish remaining blocks
        finishCurrentBlock(currentBlockType, output, stream);

        // Finish tool calls
        for (Map.Entry<String, Integer> entry : toolBlocksByKey.entrySet()) {
            int idx = entry.getValue();
            if (idx < output.getContent().size() && output.getContent().get(idx) instanceof ToolCall tc) {
                StringBuilder partial = toolPartialArgs.get(idx);
                if (partial != null) {
                    Map<String, Object> args = StreamingJsonParser.parseStreamingJson(partial.toString());
                    ToolCall finalTc = new ToolCall("toolCall", tc.id(), tc.name(), args, tc.thoughtSignature());
                    output.getContent().set(idx, finalTc);
                    stream.push(new AssistantMessageEvent.ToolCallEnd(idx, finalTc, output));
                } else {
                    stream.push(new AssistantMessageEvent.ToolCallEnd(idx, tc, output));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String handleThinkingDelta(Map<String, Object> itemMap, AssistantMessage output,
                                        AssistantMessageEventStream stream, String currentBlockType) {
        List<Map<String, Object>> thinkingParts = (List<Map<String, Object>>) itemMap.get("thinking");
        if (thinkingParts == null) return currentBlockType;

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> part : thinkingParts) {
            String text = (String) part.get("text");
            if (text != null && !text.isEmpty()) sb.append(text);
        }
        String thinkingDelta = UnicodeSanitizer.sanitizeSurrogates(sb.toString());
        if (thinkingDelta.isEmpty()) return currentBlockType;

        if (!"thinking".equals(currentBlockType)) {
            finishCurrentBlock(currentBlockType, output, stream);
            output.getContent().add(new ThinkingContent("thinking", "", null, null));
            stream.push(new AssistantMessageEvent.ThinkingStart(
                    output.getContent().size() - 1, output));
            currentBlockType = "thinking";
        }
        int idx = output.getContent().size() - 1;
        ThinkingContent tc = (ThinkingContent) output.getContent().get(idx);
        output.getContent().set(idx, new ThinkingContent("thinking",
                tc.thinking() + thinkingDelta, tc.thinkingSignature(), tc.redacted()));
        stream.push(new AssistantMessageEvent.ThinkingDelta(idx, thinkingDelta, output));
        return currentBlockType;
    }

    @SuppressWarnings("unchecked")
    private void processToolCallDelta(Map<String, Object> tcDelta, AssistantMessage output,
                                       AssistantMessageEventStream stream,
                                       Map<String, Integer> toolBlocksByKey,
                                       Map<Integer, StringBuilder> toolPartialArgs) {
        String callId = (String) tcDelta.get("id");
        if (callId == null || "null".equals(callId)) {
            int index = tcDelta.containsKey("index") ? ((Number) tcDelta.get("index")).intValue() : 0;
            callId = deriveMistralToolCallId("toolcall:" + index, 0);
        }
        int index = tcDelta.containsKey("index") ? ((Number) tcDelta.get("index")).intValue() : 0;
        String key = callId + ":" + index;

        Integer existingIdx = toolBlocksByKey.get(key);
        Map<String, Object> function = (Map<String, Object>) tcDelta.get("function");
        String name = function != null ? (String) function.get("name") : "";

        if (existingIdx == null) {
            // New tool call
            ToolCall tc = new ToolCall("toolCall", callId, name != null ? name : "", Map.of(), null);
            output.getContent().add(tc);
            int idx = output.getContent().size() - 1;
            toolBlocksByKey.put(key, idx);
            toolPartialArgs.put(idx, new StringBuilder());
            stream.push(new AssistantMessageEvent.ToolCallStart(idx, output));
            existingIdx = idx;
        }

        // Accumulate arguments
        Object argsObj = function != null ? function.get("arguments") : null;
        String argsDelta = argsObj instanceof String s ? s : (argsObj != null ? toJson(argsObj) : "");
        if (!argsDelta.isEmpty()) {
            StringBuilder partial = toolPartialArgs.get(existingIdx);
            if (partial != null) {
                partial.append(argsDelta);
                Map<String, Object> args = StreamingJsonParser.parseStreamingJson(partial.toString());
                ToolCall tc = (ToolCall) output.getContent().get(existingIdx);
                output.getContent().set(existingIdx,
                        new ToolCall("toolCall", tc.id(), tc.name(), args, tc.thoughtSignature()));
            }
            stream.push(new AssistantMessageEvent.ToolCallDelta(existingIdx, argsDelta, output));
        }
    }

    private void finishCurrentBlock(String blockType, AssistantMessage output,
                                     AssistantMessageEventStream stream) {
        if (blockType == null || output.getContent().isEmpty()) return;
        int idx = output.getContent().size() - 1;
        AssistantContentBlock block = output.getContent().get(idx);
        if ("text".equals(blockType) && block instanceof TextContent tc) {
            stream.push(new AssistantMessageEvent.TextEnd(idx, tc.text(), output));
        } else if ("thinking".equals(blockType) && block instanceof ThinkingContent tc) {
            stream.push(new AssistantMessageEvent.ThinkingEnd(idx, tc.thinking(), output));
        }
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultValue;
    }

    static StopReason mapStopReason(String reason) {
        if (reason == null) return StopReason.STOP;
        return switch (reason) {
            case "stop" -> StopReason.STOP;
            case "length", "model_length" -> StopReason.LENGTH;
            case "tool_calls" -> StopReason.TOOL_USE;
            case "error" -> StopReason.ERROR;
            default -> StopReason.STOP;
        };
    }

    private String getPromptMode(StreamOptions options) {
        if (options == null || options.getMetadata() == null) return null;
        Object val = options.getMetadata().get("_prompt_mode");
        return val instanceof String s ? s : null;
    }

    // ========== Mistral Tool Call ID Normalization ==========

    static String deriveMistralToolCallId(String id, int attempt) {
        String normalized = id.replaceAll("[^a-zA-Z0-9]", "");
        if (attempt == 0 && normalized.length() == MISTRAL_TOOL_CALL_ID_LENGTH) return normalized;
        String seedBase = normalized.isEmpty() ? id : normalized;
        String seed = attempt == 0 ? seedBase : seedBase + ":" + attempt;
        return ShortHash.shortHash(seed)
                .replaceAll("[^a-zA-Z0-9]", "")
                .substring(0, Math.min(MISTRAL_TOOL_CALL_ID_LENGTH,
                        ShortHash.shortHash(seed).replaceAll("[^a-zA-Z0-9]", "").length()));
    }

    /**
     * Mistral Tool Call ID 规范化器，确保唯一性。
     */
    private static class MistralToolCallIdNormalizer {
        private final Map<String, String> idMap = new HashMap<>();
        private final Map<String, String> reverseMap = new HashMap<>();

        String normalize(String id) {
            String existing = idMap.get(id);
            if (existing != null) return existing;

            int attempt = 0;
            while (true) {
                String candidate = deriveMistralToolCallId(id, attempt);
                String owner = reverseMap.get(candidate);
                if (owner == null || owner.equals(id)) {
                    idMap.put(id, candidate);
                    reverseMap.put(candidate, id);
                    return candidate;
                }
                attempt++;
            }
        }
    }
}
