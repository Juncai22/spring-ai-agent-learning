package com.pi.ai.provider.openai;

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
 * OpenAI Responses API Provider（openai-responses）——对接 OpenAI Responses API 的实现。
 *
 * <p>本类实现了与 OpenAI Responses API 的集成，使用 OpenAI 最新的 Responses API 格式
 * （{@code /v1/responses} 端点）。与传统的 Chat Completions API 不同，Responses API
 * 使用 "input + instructions" 模式，支持更丰富的消息类型和响应格式。
 *
 * <h3>支持的 OpenAI 特性</h3>
 * <ul>
 *   <li><b>流式消息生成（SSE）</b>：通过 text/event-stream 响应流式返回推理结果</li>
 *   <li><b>Reasoning（推理）</b>：支持 o1/o3 等推理模型的 reasoning_effort 配置</li>
 *   <li><b>Function Calling</b>：支持函数调用，使用 function_call 和 function_call_output 类型</li>
 *   <li><b>Text Signature</b>：思考内容的签名，用于跨轮对话的连续性</li>
 *   <li><b>Session Caching</b>：通过 prompt_cache_key 实现会话级缓存</li>
 *   <li><b>Developer 角色</b>：使用 role="developer" 替代 system prompt</li>
 * </ul>
 *
 * <h3>消息格式</h3>
 * <p>OpenAI Responses API 使用独特的消息格式：
 * <ul>
 *   <li>输入消息使用 "input" 字段而非 "messages"</li>
 *   <li>支持 developer / user / assistant 角色</li>
 *   <li>助手消息需要拆分为 "output items"（message、function_call 等）</li>
 *   <li>工具结果使用 "function_call_output" 类型</li>
 *   <li>思考内容使用 "reasoning" 类型的 item</li>
 *   <li>ToolCall ID 使用 pipe-separated 格式：{call_id}|{item_id}</li>
 * </ul>
 *
 * <h3>SSE 事件类型</h3>
 * <p>OpenAI Responses API 的 SSE 事件流包含以下事件类型：
 * <ul>
 *   <li>{@code response.output_item.added}：新的输出项开始</li>
 *   <li>{@code response.reasoning_summary_text.delta}：推理内容增量</li>
 *   <li>{@code response.output_text.delta}：文本内容增量</li>
 *   <li>{@code response.function_call_arguments.delta}：函数调用参数增量</li>
 *   <li>{@code response.output_item.done}：输出项完成</li>
 *   <li>{@code response.completed}：响应完成，包含用量</li>
 *   <li>{@code response.failed}：响应失败</li>
 *   <li>{@code error}：错误事件</li>
 * </ul>
 *
 * @see <a href="https://platform.openai.com/docs/api-reference/responses">OpenAI Responses API</a>
 */
public class OpenAIResponsesProvider extends BaseProvider implements ApiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAIResponsesProvider.class);
    private static final String API_ID = "openai-responses";

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
                Map<String, Object> params = buildParams(model, context, options);
                String url = (model.baseUrl() != null ? model.baseUrl() : "https://api.openai.com/v1")
                        + "/responses";

                Map<String, String> headers = buildHeaders(model, options, apiKey);
                String body = toJson(params);

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
        String apiKey = (options != null ? options.getApiKey() : null);
        if (apiKey == null) apiKey = EnvApiKeys.getEnvApiKey(model.provider());
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("No API key for provider: " + model.provider());
        }

        StreamOptions base = SimpleOptions.buildBaseOptions(model, options, apiKey);
        ThinkingLevel reasoning = options != null ? options.getReasoning() : null;
        ThinkingLevel clamped = ModelRegistry.supportsXhigh(model) ? reasoning : SimpleOptions.clampReasoning(reasoning);

        Map<String, Object> meta = new LinkedHashMap<>();
        if (base.getMetadata() != null) meta.putAll(base.getMetadata());
        if (clamped != null) meta.put("_reasoning_effort", clamped);

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
        return headers;
    }

    private Map<String, Object> buildParams(Model model, Context context, StreamOptions options) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", model.id());
        params.put("stream", true);
        params.put("store", false);

        // input（消息转换为 Responses API 格式）
        List<Map<String, Object>> input = convertMessages(model, context);
        params.put("input", input);

        // session caching
        if (options != null && options.getSessionId() != null) {
            params.put("prompt_cache_key", options.getSessionId());
        }

        if (options != null && options.getMaxTokens() != null) {
            params.put("max_output_tokens", options.getMaxTokens());
        }
        if (options != null && options.getTemperature() != null) {
            params.put("temperature", options.getTemperature());
        }

        // tools
        if (context.tools() != null && !context.tools().isEmpty()) {
            params.put("tools", convertTools(context.tools()));
        }

        // reasoning
        ThinkingLevel reasoningEffort = getReasoningEffortFromMetadata(options);
        if (model.reasoning() && reasoningEffort != null) {
            params.put("reasoning", Map.of(
                    "effort", reasoningEffort.name().toLowerCase(),
                    "summary", "auto"));
            params.put("include", List.of("reasoning.encrypted_content"));
        }

        return params;
    }

    private List<Map<String, Object>> convertMessages(Model model, Context context) {
        List<Message> transformed = MessageTransformer.transformMessages(
                context.messages(), model, null);

        List<Map<String, Object>> result = new ArrayList<>();

        // system prompt → instructions（developer 角色）
        if (context.systemPrompt() != null && !context.systemPrompt().isEmpty()) {
            result.add(Map.of("role", "developer",
                    "content", List.of(Map.of("type", "input_text",
                            "text", UnicodeSanitizer.sanitizeSurrogates(context.systemPrompt())))));
        }

        for (int i = 0; i < transformed.size(); i++) {
            Message msg = transformed.get(i);

            if (msg instanceof UserMessage um) {
                Map<String, Object> param = convertUserMessage(um, model);
                if (param != null) result.add(param);

            } else if (msg instanceof AssistantMessage am) {
                // Responses API 中 assistant 消息需要拆分为 output items
                convertAssistantToOutputItems(am, result);

            } else if (msg instanceof ToolResultMessage trm) {
                // 收集连续 toolResult
                int j = i;
                while (j < transformed.size() && transformed.get(j) instanceof ToolResultMessage) {
                    ToolResultMessage toolMsg = (ToolResultMessage) transformed.get(j);
                    result.add(convertToolResult(toolMsg));
                    j++;
                }
                i = j - 1;
            }
        }

        return result;
    }

    private Map<String, Object> convertUserMessage(UserMessage um, Model model) {
        Object content = um.content();
        if (content instanceof String text) {
            return Map.of("role", "user",
                    "content", List.of(Map.of("type", "input_text",
                            "text", UnicodeSanitizer.sanitizeSurrogates(text))));
        }
        if (content instanceof List<?> blocks) {
            List<Map<String, Object>> parts = new ArrayList<>();
            for (Object block : blocks) {
                if (block instanceof TextContent tc) {
                    parts.add(Map.of("type", "input_text",
                            "text", UnicodeSanitizer.sanitizeSurrogates(tc.text())));
                } else if (block instanceof ImageContent ic) {
                    if (model.input() != null && model.input().contains("image")) {
                        parts.add(Map.of("type", "input_image",
                                "image_url", "data:" + ic.mimeType() + ";base64," + ic.data()));
                    }
                }
            }
            if (parts.isEmpty()) return null;
            return Map.of("role", "user", "content", parts);
        }
        return null;
    }

    private void convertAssistantToOutputItems(AssistantMessage am, List<Map<String, Object>> result) {
        for (AssistantContentBlock block : am.getContent()) {
            if (block instanceof TextContent tc) {
                if (tc.text() != null && !tc.text().trim().isEmpty()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", "message");
                    item.put("role", "assistant");
                    item.put("content", List.of(Map.of("type", "output_text",
                            "text", UnicodeSanitizer.sanitizeSurrogates(tc.text()))));
                    result.add(item);
                }
            } else if (block instanceof ThinkingContent tc) {
                // thinking 块作为 reasoning item 传回
                if (tc.thinkingSignature() != null) {
                    try {
                        Map<String, Object> reasoningItem = parseJson(tc.thinkingSignature());
                        result.add(reasoningItem);
                    } catch (Exception e) {
                        // 无法解析 signature，跳过
                    }
                }
            } else if (block instanceof ToolCall tc) {
                // function_call item
                String callId = tc.id();
                String itemId = null;
                // 解析 pipe-separated ID: {call_id}|{item_id}
                if (callId != null && callId.contains("|")) {
                    String[] parts = callId.split("\\|", 2);
                    callId = parts[0];
                    itemId = parts[1];
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "function_call");
                item.put("call_id", callId);
                if (itemId != null) item.put("id", itemId);
                item.put("name", tc.name());
                item.put("arguments", toJson(tc.arguments()));
                result.add(item);
            }
        }
    }

    private Map<String, Object> convertToolResult(ToolResultMessage trm) {
        String callId = trm.toolCallId();
        // 解析 pipe-separated ID
        if (callId != null && callId.contains("|")) {
            callId = callId.split("\\|")[0];
        }
        String textResult = extractTextContent(trm);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "function_call_output");
        item.put("call_id", callId);
        item.put("output", textResult.isEmpty() ? "(no content)" : textResult);
        return item;
    }

    private List<Map<String, Object>> convertTools(List<Tool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Tool tool : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("type", "function");
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            if (tool.parameters() != null) {
                fn.put("parameters", PiAiJson.MAPPER.convertValue(tool.parameters(), Map.class));
            }
            fn.put("strict", false);
            result.add(fn);
        }
        return result;
    }

    private String extractTextContent(ToolResultMessage trm) {
        if (trm.content() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (UserContentBlock block : trm.content()) {
            if (block instanceof TextContent tc) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    // ========== SSE 事件处理 ==========

    @SuppressWarnings("unchecked")
    private void processSseStream(InputStream inputStream, AssistantMessageEventStream stream,
                                   AssistantMessage output, Model model) {
        String currentItemType = null; // "reasoning", "message", "function_call"
        StringBuilder partialArgs = new StringBuilder();

        Iterator<SseParser.SseEvent> sseIter = SseParser.parse(inputStream);
        while (sseIter.hasNext()) {
            SseParser.SseEvent sseEvent = sseIter.next();
            if (sseEvent.isDone()) break;
            if (sseEvent.isError()) throw new RuntimeException(sseEvent.data());
            if (sseEvent.data() == null || sseEvent.data().isEmpty()) continue;

            Map<String, Object> data;
            try {
                data = parseJson(sseEvent.data());
            } catch (Exception e) {
                continue;
            }

            String type = (String) data.get("type");
            if (type == null) continue;

            switch (type) {
                case "response.output_item.added" -> {
                    Map<String, Object> item = (Map<String, Object>) data.get("item");
                    if (item == null) break;
                    String itemType = (String) item.get("type");
                    currentItemType = itemType;

                    if ("reasoning".equals(itemType)) {
                        output.getContent().add(new ThinkingContent("thinking", "", null, null));
                        stream.push(new AssistantMessageEvent.ThinkingStart(
                                output.getContent().size() - 1, output));
                    } else if ("message".equals(itemType)) {
                        output.getContent().add(new TextContent("text", "", null));
                        stream.push(new AssistantMessageEvent.TextStart(
                                output.getContent().size() - 1, output));
                    } else if ("function_call".equals(itemType)) {
                        String callId = (String) item.get("call_id");
                        String itemId = (String) item.get("id");
                        String name = (String) item.get("name");
                        String id = callId + (itemId != null ? "|" + itemId : "");
                        partialArgs.setLength(0);
                        output.getContent().add(new ToolCall("toolCall", id,
                                name != null ? name : "", Map.of(), null));
                        stream.push(new AssistantMessageEvent.ToolCallStart(
                                output.getContent().size() - 1, output));
                    }
                }
                case "response.reasoning_summary_text.delta" -> {
                    String delta = (String) data.get("delta");
                    if (delta != null && !output.getContent().isEmpty()) {
                        int idx = output.getContent().size() - 1;
                        if (output.getContent().get(idx) instanceof ThinkingContent tc) {
                            output.getContent().set(idx, new ThinkingContent("thinking",
                                    tc.thinking() + delta, tc.thinkingSignature(), tc.redacted()));
                            stream.push(new AssistantMessageEvent.ThinkingDelta(idx, delta, output));
                        }
                    }
                }
                case "response.output_text.delta" -> {
                    String delta = (String) data.get("delta");
                    if (delta != null && !output.getContent().isEmpty()) {
                        int idx = output.getContent().size() - 1;
                        if (output.getContent().get(idx) instanceof TextContent tc) {
                            output.getContent().set(idx, new TextContent("text",
                                    tc.text() + delta, tc.textSignature()));
                            stream.push(new AssistantMessageEvent.TextDelta(idx, delta, output));
                        }
                    }
                }
                case "response.function_call_arguments.delta" -> {
                    String delta = (String) data.get("delta");
                    if (delta != null && !output.getContent().isEmpty()) {
                        partialArgs.append(delta);
                        int idx = output.getContent().size() - 1;
                        if (output.getContent().get(idx) instanceof ToolCall tc) {
                            Map<String, Object> args = StreamingJsonParser.parseStreamingJson(partialArgs.toString());
                            output.getContent().set(idx, new ToolCall("toolCall", tc.id(),
                                    tc.name(), args, tc.thoughtSignature()));
                            stream.push(new AssistantMessageEvent.ToolCallDelta(idx, delta, output));
                        }
                    }
                }
                case "response.output_item.done" -> {
                    Map<String, Object> item = (Map<String, Object>) data.get("item");
                    if (item == null || output.getContent().isEmpty()) break;
                    int idx = output.getContent().size() - 1;
                    String itemType = (String) item.get("type");

                    if ("reasoning".equals(itemType) && output.getContent().get(idx) instanceof ThinkingContent tc) {
                        // 存储完整 reasoning item 作为 thinkingSignature
                        String sig = toJson(item);
                        output.getContent().set(idx, new ThinkingContent("thinking",
                                tc.thinking(), sig, tc.redacted()));
                        stream.push(new AssistantMessageEvent.ThinkingEnd(idx, tc.thinking(), output));
                    } else if ("message".equals(itemType) && output.getContent().get(idx) instanceof TextContent tc) {
                        stream.push(new AssistantMessageEvent.TextEnd(idx, tc.text(), output));
                    } else if ("function_call".equals(itemType) && output.getContent().get(idx) instanceof ToolCall tc) {
                        Map<String, Object> args = partialArgs.length() > 0
                                ? StreamingJsonParser.parseStreamingJson(partialArgs.toString())
                                : tc.arguments();
                        ToolCall finalTc = new ToolCall("toolCall", tc.id(), tc.name(), args, tc.thoughtSignature());
                        output.getContent().set(idx, finalTc);
                        stream.push(new AssistantMessageEvent.ToolCallEnd(idx, finalTc, output));
                        partialArgs.setLength(0);
                    }
                    currentItemType = null;
                }
                case "response.completed" -> {
                    Map<String, Object> response = (Map<String, Object>) data.get("response");
                    if (response != null) {
                        Map<String, Object> usage = (Map<String, Object>) response.get("usage");
                        if (usage != null) {
                            output.setUsage(parseUsage(usage, model));
                        }
                        String status = (String) response.get("status");
                        output.setStopReason(mapStatus(status));
                        // 如果有 toolCall 且 stopReason 是 stop，改为 toolUse
                        boolean hasToolCall = output.getContent().stream()
                                .anyMatch(b -> b instanceof ToolCall);
                        if (hasToolCall && output.getStopReason() == StopReason.STOP) {
                            output.setStopReason(StopReason.TOOL_USE);
                        }
                    }
                }
                case "error" -> {
                    String code = String.valueOf(data.get("code"));
                    String message = (String) data.get("message");
                    throw new RuntimeException("Error " + code + ": " + message);
                }
                case "response.failed" -> {
                    Map<String, Object> response = (Map<String, Object>) data.get("response");
                    String msg = "Unknown error";
                    if (response != null) {
                        Map<String, Object> error = (Map<String, Object>) response.get("error");
                        if (error != null) {
                            msg = error.get("code") + ": " + error.get("message");
                        }
                    }
                    throw new RuntimeException(msg);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Usage parseUsage(Map<String, Object> rawUsage, Model model) {
        int inputTokens = getInt(rawUsage, "input_tokens", 0);
        int outputTokens = getInt(rawUsage, "output_tokens", 0);
        int totalTokens = getInt(rawUsage, "total_tokens", 0);

        int cachedTokens = 0;
        Object details = rawUsage.get("input_tokens_details");
        if (details instanceof Map<?,?> d) {
            cachedTokens = getInt((Map<String, Object>) d, "cached_tokens", 0);
        }

        int input = inputTokens - cachedTokens;
        Usage usage = new Usage(input, outputTokens, cachedTokens, 0, totalTokens, null);
        Usage.Cost cost = ModelRegistry.calculateCost(model, usage);
        return new Usage(input, outputTokens, cachedTokens, 0, totalTokens, cost);
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultValue;
    }

    private StopReason mapStatus(String status) {
        if (status == null) return StopReason.STOP;
        return switch (status) {
            case "completed" -> StopReason.STOP;
            case "incomplete" -> StopReason.LENGTH;
            case "failed", "cancelled" -> StopReason.ERROR;
            default -> StopReason.STOP;
        };
    }

    private ThinkingLevel getReasoningEffortFromMetadata(StreamOptions options) {
        if (options == null || options.getMetadata() == null) return null;
        Object val = options.getMetadata().get("_reasoning_effort");
        return (val instanceof ThinkingLevel tl) ? tl : null;
    }
}
