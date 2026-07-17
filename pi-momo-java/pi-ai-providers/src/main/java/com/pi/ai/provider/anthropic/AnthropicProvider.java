package com.pi.ai.provider.anthropic;

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
 * Anthropic Messages API Provider（anthropic-messages）——对接 Anthropic 消息 API 的实现。
 *
 * <p>本类实现了与 Anthropic Claude 模型 API 的集成，使用 Anthropic Messages API
 * 格式（{@code /v1/messages} 端点）。Anthropic 的 API 使用 content block 数组格式，
 * 支持多种类型的内容块：文本、thinking、工具调用等。
 *
 * <h3>支持的 Anthropic 特性</h3>
 * <ul>
 *   <li><b>流式消息生成（SSE）</b>：通过 text/event-stream 响应流式返回推理结果</li>
 *   <li><b>Extended Thinking / Adaptive Thinking</b>：支持 Claude 的思考过程展示，
 *       包括自适应思考（Opus 4.6 / Sonnet 4.6）和基于预算的思考（旧模型）</li>
 *   <li><b>Prompt Caching</b>：通过 cache_control 标记实现提示缓存，减少重复计算</li>
 *   <li><b>工具调用</b>：支持 function calling，包括细粒度工具流式输出</li>
 *   <li><b>HTTP 429/529 重试</b>：自动处理限流和临时服务不可用</li>
 *   <li><b>OAuth 认证</b>：支持 sk-ant-oat 开头的 OAuth token</li>
 *   <li><b>Beta 特性</b>：通过 anthropic-beta 头启用细粒度工具流式输出等 Beta 功能</li>
 * </ul>
 *
 * <h3>消息格式</h3>
 * <p>Anthropic API 使用独特的消息格式：
 * <ul>
 *   <li>用户消息：role="user"，content 可以是字符串或 content block 数组</li>
 *   <li>助手消息：role="assistant"，content 包含文本、thinking、tool_use 等 block</li>
 *   <li>工具结果：role="user"，content 为 tool_result block 数组</li>
 *   <li>系统提示：独立的 system 参数，非 messages 数组的一部分</li>
 * </ul>
 *
 * <h3>SSE 事件类型</h3>
 * <p>Anthropic 的 SSE 事件流包含以下事件类型：
 * <ul>
 *   <li>{@code message_start}：消息开始，包含初始用量</li>
 *   <li>{@code content_block_start}：内容块开始，包含类型和索引</li>
 *   <li>{@code content_block_delta}：内容块增量更新</li>
 *   <li>{@code content_block_stop}：内容块完成</li>
 *   <li>{@code message_delta}：消息增量更新，包含 stop_reason 和最终用量</li>
 *   <li>{@code message_stop}：消息结束</li>
 * </ul>
 */
public class AnthropicProvider extends BaseProvider implements ApiProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);

    private static final String API_ID = "anthropic-messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

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
                Map<String, Object> params = buildParams(model, context, options, apiKey);
                String url = (model.baseUrl() != null ? model.baseUrl() : "https://api.anthropic.com")
                        + "/v1/messages";

                Map<String, String> headers = buildHeaders(model, options, apiKey);
                String body = toJson(params);

                var request = buildPostRequest(url, body, headers);
                HttpResponse<InputStream> response = sendWithRetry(request, options);

                // 解析 SSE 事件流
                stream.push(new AssistantMessageEvent.Start(output));
                processSseStream(response.body(), stream, output, model);

                // 检查取消信号
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
        if (apiKey == null) {
            apiKey = EnvApiKeys.getEnvApiKey(model.provider());
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("No API key for provider: " + model.provider());
        }

        StreamOptions base = SimpleOptions.buildBaseOptions(model, options, apiKey);

        if (options == null || options.getReasoning() == null) {
            return stream(model, context, base);
        }

        // adaptive thinking（Opus 4.6 / Sonnet 4.6）
        if (supportsAdaptiveThinking(model.id())) {
            // adaptive thinking 通过 stream 方法的 metadata 传递 effort 参数
            // 在 buildParams 中处理
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
                    .metadata(buildThinkingMetadata(true, options.getReasoning(), model.id(), 0))
                    .build();
            return stream(model, context, adapted);
        }

        // budget-based thinking（旧模型）
        SimpleOptions.ThinkingResult adjusted = SimpleOptions.adjustMaxTokensForThinking(
                base.getMaxTokens() != null ? base.getMaxTokens() : 0,
                model.maxTokens(),
                options.getReasoning(),
                options.getThinkingBudgets());

        StreamOptions adapted = StreamOptions.builder()
                .temperature(base.getTemperature())
                .maxTokens(adjusted.maxTokens())
                .apiKey(base.getApiKey())
                .cacheRetention(base.getCacheRetention())
                .sessionId(base.getSessionId())
                .headers(base.getHeaders())
                .transport(base.getTransport())
                .maxRetryDelayMs(base.getMaxRetryDelayMs())
                .signal(base.getSignal())
                .metadata(buildThinkingMetadata(true, options.getReasoning(), model.id(), adjusted.thinkingBudget()))
                .build();
        return stream(model, context, adapted);
    }

    // ========== 内部方法 ==========

    private String resolveApiKey(Model model, StreamOptions options) {
        String apiKey = options != null ? options.getApiKey() : null;
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = EnvApiKeys.getEnvApiKey(model.provider());
        }
        return apiKey != null ? apiKey : "";
    }

    private Map<String, String> buildHeaders(Model model, StreamOptions options, String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        headers.put("accept", "application/json");

        // Beta features
        List<String> betaFeatures = new ArrayList<>();
        betaFeatures.add("fine-grained-tool-streaming-2025-05-14");
        if (!supportsAdaptiveThinking(model.id())) {
            betaFeatures.add("interleaved-thinking-2025-05-14");
        }
        headers.put("anthropic-beta", String.join(",", betaFeatures));

        // 认证
        if (isOAuthToken(apiKey)) {
            headers.put("Authorization", "Bearer " + apiKey);
        } else if (!apiKey.isEmpty()) {
            headers.put("x-api-key", apiKey);
        }

        // 模型自定义 headers
        if (model.headers() != null) {
            headers.putAll(model.headers());
        }
        // 选项自定义 headers
        if (options != null && options.getHeaders() != null) {
            headers.putAll(options.getHeaders());
        }

        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildParams(Model model, Context context, StreamOptions options, String apiKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", model.id());
        params.put("stream", true);

        int maxTokens = (options != null && options.getMaxTokens() != null)
                ? options.getMaxTokens()
                : model.maxTokens() / 3;
        params.put("max_tokens", maxTokens);

        // 消息转换
        List<Map<String, Object>> messages = convertMessages(context, model);
        params.put("messages", messages);

        // system prompt
        if (context.systemPrompt() != null && !context.systemPrompt().isEmpty()) {
            params.put("system", List.of(Map.of(
                    "type", "text",
                    "text", UnicodeSanitizer.sanitizeSurrogates(context.systemPrompt()))));
        }

        // temperature（与 thinking 不兼容）
        boolean thinkingEnabled = isThinkingEnabled(options);
        if (!thinkingEnabled && options != null && options.getTemperature() != null) {
            params.put("temperature", options.getTemperature());
        }

        // 工具
        if (context.tools() != null && !context.tools().isEmpty()) {
            params.put("tools", convertTools(context.tools()));
        }

        // thinking 配置
        if (thinkingEnabled && model.reasoning()) {
            if (supportsAdaptiveThinking(model.id())) {
                params.put("thinking", Map.of("type", "adaptive"));
                String effort = getEffortFromMetadata(options);
                if (effort != null) {
                    params.put("output_config", Map.of("effort", effort));
                }
            } else {
                int budget = getBudgetFromMetadata(options);
                params.put("thinking", Map.of(
                        "type", "enabled",
                        "budget_tokens", budget > 0 ? budget : 1024));
            }
        }

        // metadata
        if (options != null && options.getMetadata() != null) {
            Object userId = options.getMetadata().get("user_id");
            if (userId instanceof String) {
                params.put("metadata", Map.of("user_id", userId));
            }
        }

        return params;
    }

    private List<Map<String, Object>> convertMessages(Context context, Model model) {
        List<Message> transformed = MessageTransformer.transformMessages(
                context.messages(), model, (id, m, src) -> normalizeToolCallId(id));

        List<Map<String, Object>> result = new ArrayList<>();

        for (int i = 0; i < transformed.size(); i++) {
            Message msg = transformed.get(i);

            if (msg instanceof UserMessage um) {
                Map<String, Object> param = convertUserMessage(um, model);
                if (param != null) result.add(param);

            } else if (msg instanceof AssistantMessage am) {
                Map<String, Object> param = convertAssistantMessage(am);
                if (param != null) result.add(param);

            } else if (msg instanceof ToolResultMessage trm) {
                // 收集连续的 toolResult 消息
                List<Map<String, Object>> toolResults = new ArrayList<>();
                toolResults.add(convertToolResult(trm));

                int j = i + 1;
                while (j < transformed.size() && transformed.get(j) instanceof ToolResultMessage nextTrm) {
                    toolResults.add(convertToolResult(nextTrm));
                    j++;
                }
                i = j - 1;

                result.add(Map.of("role", "user", "content", toolResults));
            }
        }

        return result;
    }

    private Map<String, Object> convertUserMessage(UserMessage um, Model model) {
        Object content = um.content();
        if (content instanceof String text) {
            if (text.trim().isEmpty()) return null;
            return Map.of("role", "user",
                    "content", UnicodeSanitizer.sanitizeSurrogates(text));
        }
        if (content instanceof List<?> blocks) {
            List<Map<String, Object>> converted = new ArrayList<>();
            for (Object block : blocks) {
                if (block instanceof TextContent tc) {
                    if (tc.text() != null && !tc.text().trim().isEmpty()) {
                        converted.add(Map.of("type", "text",
                                "text", UnicodeSanitizer.sanitizeSurrogates(tc.text())));
                    }
                } else if (block instanceof ImageContent ic) {
                    if (model.input() != null && model.input().contains("image")) {
                        converted.add(Map.of("type", "image",
                                "source", Map.of(
                                        "type", "base64",
                                        "media_type", ic.mimeType(),
                                        "data", ic.data())));
                    }
                }
            }
            if (converted.isEmpty()) return null;
            return Map.of("role", "user", "content", converted);
        }
        return null;
    }

    private Map<String, Object> convertAssistantMessage(AssistantMessage am) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (AssistantContentBlock block : am.getContent()) {
            if (block instanceof TextContent tc) {
                if (tc.text() != null && !tc.text().trim().isEmpty()) {
                    blocks.add(Map.of("type", "text",
                            "text", UnicodeSanitizer.sanitizeSurrogates(tc.text())));
                }
            } else if (block instanceof ThinkingContent tc) {
                if (tc.redacted() != null && tc.redacted()) {
                    blocks.add(Map.of("type", "redacted_thinking",
                            "data", tc.thinkingSignature()));
                } else if (tc.thinking() != null && !tc.thinking().trim().isEmpty()) {
                    if (tc.thinkingSignature() != null && !tc.thinkingSignature().trim().isEmpty()) {
                        blocks.add(Map.of("type", "thinking",
                                "thinking", UnicodeSanitizer.sanitizeSurrogates(tc.thinking()),
                                "signature", tc.thinkingSignature()));
                    } else {
                        // 无 signature 的 thinking 转为 text
                        blocks.add(Map.of("type", "text",
                                "text", UnicodeSanitizer.sanitizeSurrogates(tc.thinking())));
                    }
                }
            } else if (block instanceof ToolCall tc) {
                Map<String, Object> toolUse = new LinkedHashMap<>();
                toolUse.put("type", "tool_use");
                toolUse.put("id", tc.id());
                toolUse.put("name", tc.name());
                toolUse.put("input", tc.arguments() != null ? tc.arguments() : Map.of());
                blocks.add(toolUse);
            }
        }
        if (blocks.isEmpty()) return null;
        return Map.of("role", "assistant", "content", blocks);
    }

    private Map<String, Object> convertToolResult(ToolResultMessage trm) {
        List<Map<String, Object>> content = new ArrayList<>();
        if (trm.content() != null) {
            for (UserContentBlock block : trm.content()) {
                if (block instanceof TextContent tc) {
                    content.add(Map.of("type", "text", "text", tc.text()));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "tool_result");
        result.put("tool_use_id", trm.toolCallId());
        if (!content.isEmpty()) {
            result.put("content", content);
        }
        result.put("is_error", trm.isError());
        return result;
    }

    private List<Map<String, Object>> convertTools(List<Tool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Tool tool : tools) {
            Map<String, Object> converted = new LinkedHashMap<>();
            converted.put("name", tool.name());
            converted.put("description", tool.description());

            // input_schema 从 tool.parameters() 提取
            Map<String, Object> inputSchema = new LinkedHashMap<>();
            inputSchema.put("type", "object");
            if (tool.parameters() != null) {
                if (tool.parameters().has("properties")) {
                    inputSchema.put("properties", PiAiJson.MAPPER.convertValue(
                            tool.parameters().get("properties"), Map.class));
                }
                if (tool.parameters().has("required")) {
                    inputSchema.put("required", PiAiJson.MAPPER.convertValue(
                            tool.parameters().get("required"), List.class));
                }
            }
            converted.put("input_schema", inputSchema);
            result.add(converted);
        }
        return result;
    }

    // ========== SSE 事件处理 ==========

    @SuppressWarnings("unchecked")
    private void processSseStream(InputStream inputStream, AssistantMessageEventStream stream,
                                   AssistantMessage output, Model model) {
        // 内容块索引映射：Anthropic event.index → output.content 索引
        Map<Integer, Integer> blockIndexMap = new HashMap<>();
        // 工具调用的 partial JSON 累积
        Map<Integer, StringBuilder> toolCallJsonBuilders = new HashMap<>();

        Iterator<SseParser.SseEvent> sseIter = SseParser.parse(inputStream);
        while (sseIter.hasNext()) {
            SseParser.SseEvent sseEvent = sseIter.next();
            if (sseEvent.isError()) {
                throw new RuntimeException(sseEvent.data());
            }
            if (sseEvent.data() == null || sseEvent.data().isEmpty()) continue;

            Map<String, Object> data;
            try {
                data = parseJson(sseEvent.data());
            } catch (Exception e) {
                log.debug("跳过无法解析的 SSE 数据: {}", sseEvent.data());
                continue;
            }

            String type = (String) data.get("type");
            if (type == null) continue;

            switch (type) {
                case "message_start" -> handleMessageStart(data, output, model);
                case "content_block_start" -> handleContentBlockStart(data, output, stream, blockIndexMap, toolCallJsonBuilders);
                case "content_block_delta" -> handleContentBlockDelta(data, output, stream, blockIndexMap, toolCallJsonBuilders);
                case "content_block_stop" -> handleContentBlockStop(data, output, stream, blockIndexMap, toolCallJsonBuilders);
                case "message_delta" -> handleMessageDelta(data, output, model);
                default -> log.debug("忽略未知 SSE 事件类型: {}", type);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleMessageStart(Map<String, Object> data, AssistantMessage output, Model model) {
        Map<String, Object> message = (Map<String, Object>) data.get("message");
        if (message == null) return;
        Map<String, Object> usage = (Map<String, Object>) message.get("usage");
        if (usage != null) {
            updateUsage(output, usage, model);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleContentBlockStart(Map<String, Object> data, AssistantMessage output,
                                          AssistantMessageEventStream stream,
                                          Map<Integer, Integer> blockIndexMap,
                                          Map<Integer, StringBuilder> toolCallJsonBuilders) {
        int eventIndex = ((Number) data.get("index")).intValue();
        Map<String, Object> contentBlock = (Map<String, Object>) data.get("content_block");
        if (contentBlock == null) return;

        String blockType = (String) contentBlock.get("type");
        int contentIndex = output.getContent().size();
        blockIndexMap.put(eventIndex, contentIndex);

        switch (blockType) {
            case "text" -> {
                output.getContent().add(new TextContent("text", "", null));
                stream.push(new AssistantMessageEvent.TextStart(contentIndex, output));
            }
            case "thinking" -> {
                output.getContent().add(new ThinkingContent("thinking", "", "", null));
                stream.push(new AssistantMessageEvent.ThinkingStart(contentIndex, output));
            }
            case "redacted_thinking" -> {
                String encryptedData = (String) contentBlock.get("data");
                output.getContent().add(new ThinkingContent("thinking",
                        "[Reasoning redacted]", encryptedData, true));
                stream.push(new AssistantMessageEvent.ThinkingStart(contentIndex, output));
            }
            case "tool_use" -> {
                String id = (String) contentBlock.get("id");
                String name = (String) contentBlock.get("name");
                output.getContent().add(new ToolCall("toolCall", id, name, Map.of(), null));
                toolCallJsonBuilders.put(eventIndex, new StringBuilder());
                stream.push(new AssistantMessageEvent.ToolCallStart(contentIndex, output));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handleContentBlockDelta(Map<String, Object> data, AssistantMessage output,
                                          AssistantMessageEventStream stream,
                                          Map<Integer, Integer> blockIndexMap,
                                          Map<Integer, StringBuilder> toolCallJsonBuilders) {
        int eventIndex = ((Number) data.get("index")).intValue();
        Integer contentIndex = blockIndexMap.get(eventIndex);
        if (contentIndex == null || contentIndex >= output.getContent().size()) return;

        Map<String, Object> delta = (Map<String, Object>) data.get("delta");
        if (delta == null) return;

        String deltaType = (String) delta.get("type");
        AssistantContentBlock block = output.getContent().get(contentIndex);

        switch (deltaType) {
            case "text_delta" -> {
                String text = (String) delta.get("text");
                if (text != null && block instanceof TextContent tc) {
                    output.getContent().set(contentIndex,
                            new TextContent("text", tc.text() + text, tc.textSignature()));
                    stream.push(new AssistantMessageEvent.TextDelta(contentIndex, text, output));
                }
            }
            case "thinking_delta" -> {
                String thinking = (String) delta.get("thinking");
                if (thinking != null && block instanceof ThinkingContent tc) {
                    output.getContent().set(contentIndex,
                            new ThinkingContent("thinking", tc.thinking() + thinking,
                                    tc.thinkingSignature(), tc.redacted()));
                    stream.push(new AssistantMessageEvent.ThinkingDelta(contentIndex, thinking, output));
                }
            }
            case "signature_delta" -> {
                String signature = (String) delta.get("signature");
                if (signature != null && block instanceof ThinkingContent tc) {
                    String existingSig = tc.thinkingSignature() != null ? tc.thinkingSignature() : "";
                    output.getContent().set(contentIndex,
                            new ThinkingContent("thinking", tc.thinking(),
                                    existingSig + signature, tc.redacted()));
                }
            }
            case "input_json_delta" -> {
                String partialJson = (String) delta.get("partial_json");
                if (partialJson != null && block instanceof ToolCall tc) {
                    StringBuilder jsonBuilder = toolCallJsonBuilders.get(eventIndex);
                    if (jsonBuilder != null) {
                        jsonBuilder.append(partialJson);
                        Map<String, Object> args = StreamingJsonParser.parseStreamingJson(jsonBuilder.toString());
                        output.getContent().set(contentIndex,
                                new ToolCall("toolCall", tc.id(), tc.name(), args, tc.thoughtSignature()));
                    }
                    stream.push(new AssistantMessageEvent.ToolCallDelta(contentIndex, partialJson, output));
                }
            }
        }
    }

    private void handleContentBlockStop(Map<String, Object> data, AssistantMessage output,
                                         AssistantMessageEventStream stream,
                                         Map<Integer, Integer> blockIndexMap,
                                         Map<Integer, StringBuilder> toolCallJsonBuilders) {
        int eventIndex = ((Number) data.get("index")).intValue();
        Integer contentIndex = blockIndexMap.get(eventIndex);
        if (contentIndex == null || contentIndex >= output.getContent().size()) return;

        AssistantContentBlock block = output.getContent().get(contentIndex);

        if (block instanceof TextContent tc) {
            stream.push(new AssistantMessageEvent.TextEnd(contentIndex, tc.text(), output));
        } else if (block instanceof ThinkingContent tc) {
            stream.push(new AssistantMessageEvent.ThinkingEnd(contentIndex, tc.thinking(), output));
        } else if (block instanceof ToolCall tc) {
            // 最终解析完整 JSON
            StringBuilder jsonBuilder = toolCallJsonBuilders.get(eventIndex);
            if (jsonBuilder != null && jsonBuilder.length() > 0) {
                Map<String, Object> args = StreamingJsonParser.parseStreamingJson(jsonBuilder.toString());
                ToolCall finalTc = new ToolCall("toolCall", tc.id(), tc.name(), args, tc.thoughtSignature());
                output.getContent().set(contentIndex, finalTc);
                stream.push(new AssistantMessageEvent.ToolCallEnd(contentIndex, finalTc, output));
            } else {
                stream.push(new AssistantMessageEvent.ToolCallEnd(contentIndex, tc, output));
            }
            toolCallJsonBuilders.remove(eventIndex);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleMessageDelta(Map<String, Object> data, AssistantMessage output, Model model) {
        Map<String, Object> delta = (Map<String, Object>) data.get("delta");
        if (delta != null) {
            String stopReason = (String) delta.get("stop_reason");
            if (stopReason != null) {
                output.setStopReason(mapStopReason(stopReason));
            }
        }
        Map<String, Object> usage = (Map<String, Object>) data.get("usage");
        if (usage != null) {
            updateUsage(output, usage, model);
        }
    }

    private void updateUsage(AssistantMessage output, Map<String, Object> usage, Model model) {
        Usage current = output.getUsage();
        int input = getInt(usage, "input_tokens", current.input());
        int outputTokens = getInt(usage, "output_tokens", current.output());
        int cacheRead = getInt(usage, "cache_read_input_tokens", current.cacheRead());
        int cacheWrite = getInt(usage, "cache_creation_input_tokens", current.cacheWrite());
        int total = input + outputTokens + cacheRead + cacheWrite;

        Usage.Cost cost = ModelRegistry.calculateCost(model,
                new Usage(input, outputTokens, cacheRead, cacheWrite, total, null));

        output.setUsage(new Usage(input, outputTokens, cacheRead, cacheWrite, total, cost));
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultValue;
    }

    // ========== 工具方法 ==========

    static StopReason mapStopReason(String reason) {
        return switch (reason) {
            case "end_turn", "pause_turn", "stop_sequence" -> StopReason.STOP;
            case "max_tokens" -> StopReason.LENGTH;
            case "tool_use" -> StopReason.TOOL_USE;
            case "refusal", "sensitive" -> StopReason.ERROR;
            default -> StopReason.STOP;
        };
    }

    static String normalizeToolCallId(String id) {
        if (id == null) return id;
        String normalized = id.replaceAll("[^a-zA-Z0-9_-]", "_");
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private static boolean isOAuthToken(String apiKey) {
        return apiKey != null && apiKey.contains("sk-ant-oat");
    }

    private static boolean supportsAdaptiveThinking(String modelId) {
        return modelId.contains("opus-4-6") || modelId.contains("opus-4.6")
                || modelId.contains("sonnet-4-6") || modelId.contains("sonnet-4.6");
    }

    // ========== thinking metadata 辅助 ==========

    private Map<String, Object> buildThinkingMetadata(boolean enabled, ThinkingLevel level,
                                                       String modelId, int budget) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("_thinking_enabled", enabled);
        if (level != null) {
            meta.put("_thinking_effort", mapThinkingLevelToEffort(level, modelId));
        }
        if (budget > 0) {
            meta.put("_thinking_budget", budget);
        }
        return meta;
    }

    private boolean isThinkingEnabled(StreamOptions options) {
        if (options == null || options.getMetadata() == null) return false;
        Object val = options.getMetadata().get("_thinking_enabled");
        return Boolean.TRUE.equals(val);
    }

    private String getEffortFromMetadata(StreamOptions options) {
        if (options == null || options.getMetadata() == null) return null;
        Object val = options.getMetadata().get("_thinking_effort");
        return val instanceof String s ? s : null;
    }

    private int getBudgetFromMetadata(StreamOptions options) {
        if (options == null || options.getMetadata() == null) return 0;
        Object val = options.getMetadata().get("_thinking_budget");
        return val instanceof Number n ? n.intValue() : 0;
    }

    private static String mapThinkingLevelToEffort(ThinkingLevel level, String modelId) {
        return switch (level) {
            case MINIMAL, LOW -> "low";
            case MEDIUM -> "medium";
            case HIGH -> "high";
            case XHIGH -> (modelId.contains("opus-4-6") || modelId.contains("opus-4.6")) ? "max" : "high";
        };
    }
}
