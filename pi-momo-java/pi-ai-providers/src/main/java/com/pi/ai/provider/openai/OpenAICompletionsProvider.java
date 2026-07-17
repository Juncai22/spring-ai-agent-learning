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
 * OpenAI Chat Completions API Provider（openai-completions）——通用 Chat Completions API 实现。
 *
 * <p>本类实现了 OpenAI Chat Completions API 格式（{@code /v1/chat/completions} 端点），
 * 同时兼容所有与 OpenAI Chat Completions API 兼容的第三方 Provider，包括：
 * Groq、xAI（Grok）、DeepSeek、Cerebras、OpenRouter、ZAI 等。
 *
 * <h3>兼容性检测</h3>
 * <p>不同的第三方 Provider 在 OpenAI 兼容 API 的基础上有不同的扩展和限制：
 * <ul>
 *   <li><b>supportsStore</b>：是否支持 {@code store} 参数（非标准 API 的 Provider 不支持）</li>
 *   <li><b>supportsDeveloperRole</b>：是否支持 developer 角色（推理模型需要）</li>
 *   <li><b>supportsReasoningEffort</b>：是否支持 reasoning_effort 参数</li>
 *   <li><b>maxTokensField</b>：使用 {@code max_tokens} 还是 {@code max_completion_tokens}</li>
 *   <li><b>requiresToolResultName</b>：toolResult 消息是否需要 name 字段</li>
 *   <li><b>requiresAssistantAfterToolResult</b>：toolResult 后是否需要插入 assistant 消息</li>
 *   <li><b>requiresThinkingAsText</b>：thinking 内容是否需要作为文本嵌入</li>
 *   <li><b>thinkingFormat</b>：thinking 格式（openai / zai / qwen）</li>
 *   <li><b>supportsStrictMode</b>：是否支持 strict 模式</li>
 * </ul>
 *
 * <h3>支持的特性</h3>
 * <ul>
 *   <li><b>流式消息生成（SSE）</b>：标准 OpenAI 流式格式</li>
 *   <li><b>Reasoning/Thinking</b>：支持多种 thinking 格式（reasoning_content、reasoning_text 等）</li>
 *   <li><b>Function Calling</b>：标准工具调用格式</li>
 *   <li><b>流式用量</b>：通过 stream_options.include_usage 获取流式用量</li>
 *   <li><b>多模态</b>：支持图片输入（base64 格式）</li>
 * </ul>
 *
 * <h3>消息格式</h3>
 * <p>使用标准的 OpenAI Chat Completions 消息格式：
 * <ul>
 *   <li>system/developer 角色作为系统提示</li>
 *   <li>user 角色支持文本和图片内容</li>
 *   <li>assistant 角色包含文本、thinking 和 tool_calls</li>
 *   <li>tool 角色用于工具结果返回</li>
 * </ul>
 *
 * @see <a href="https://platform.openai.com/docs/api-reference/chat/create">OpenAI Chat Completions API</a>
 */
public class OpenAICompletionsProvider extends BaseProvider implements ApiProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompletionsProvider.class);
    private static final String API_ID = "openai-completions";

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
                ResolvedCompat compat = getCompat(model);
                Map<String, Object> params = buildParams(model, context, options, compat);
                String url = (model.baseUrl() != null ? model.baseUrl() : "https://api.openai.com/v1")
                        + "/chat/completions";

                Map<String, String> headers = buildHeaders(model, options, apiKey);
                String body = toJson(params);

                var request = buildPostRequest(url, body, headers);
                HttpResponse<InputStream> response = sendWithRetry(request, options);

                stream.push(new AssistantMessageEvent.Start(output));
                processSseStream(response.body(), stream, output, model, compat);

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

        // 通过 metadata 传递 reasoningEffort
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
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = EnvApiKeys.getEnvApiKey(model.provider());
        }
        return apiKey != null ? apiKey : "";
    }

    private Map<String, String> buildHeaders(Model model, StreamOptions options, String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        if (model.headers() != null) headers.putAll(model.headers());
        if (options != null && options.getHeaders() != null) headers.putAll(options.getHeaders());
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildParams(Model model, Context context, StreamOptions options,
                                             ResolvedCompat compat) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", model.id());
        params.put("stream", true);

        if (compat.supportsUsageInStreaming) {
            params.put("stream_options", Map.of("include_usage", true));
        }
        if (compat.supportsStore) {
            params.put("store", false);
        }

        // maxTokens
        if (options != null && options.getMaxTokens() != null) {
            if ("max_tokens".equals(compat.maxTokensField)) {
                params.put("max_tokens", options.getMaxTokens());
            } else {
                params.put("max_completion_tokens", options.getMaxTokens());
            }
        }

        // temperature
        if (options != null && options.getTemperature() != null) {
            params.put("temperature", options.getTemperature());
        }

        // messages
        List<Map<String, Object>> messages = convertMessages(model, context, compat);
        params.put("messages", messages);

        // tools
        if (context.tools() != null && !context.tools().isEmpty()) {
            params.put("tools", convertTools(context.tools(), compat));
        }

        // reasoning_effort
        ThinkingLevel reasoningEffort = getReasoningEffortFromMetadata(options);
        if (reasoningEffort != null && model.reasoning() && compat.supportsReasoningEffort) {
            String effort = mapReasoningEffort(reasoningEffort, compat.reasoningEffortMap);
            params.put("reasoning_effort", effort);
        }

        // thinking format 特殊处理
        if ("zai".equals(compat.thinkingFormat) && model.reasoning()) {
            params.put("enable_thinking", reasoningEffort != null);
        } else if ("qwen".equals(compat.thinkingFormat) && model.reasoning()) {
            params.put("enable_thinking", reasoningEffort != null);
        }

        return params;
    }

    private List<Map<String, Object>> convertMessages(Model model, Context context, ResolvedCompat compat) {
        List<Message> transformed = MessageTransformer.transformMessages(
                context.messages(), model, (id, m, src) -> normalizeToolCallId(id, model));

        List<Map<String, Object>> result = new ArrayList<>();

        // system prompt
        if (context.systemPrompt() != null && !context.systemPrompt().isEmpty()) {
            String role = (model.reasoning() && compat.supportsDeveloperRole) ? "developer" : "system";
            result.add(Map.of("role", role,
                    "content", UnicodeSanitizer.sanitizeSurrogates(context.systemPrompt())));
        }

        String lastRole = null;

        for (int i = 0; i < transformed.size(); i++) {
            Message msg = transformed.get(i);

            // 某些 Provider 不允许 toolResult 后直接跟 user 消息
            if (compat.requiresAssistantAfterToolResult
                    && "toolResult".equals(lastRole) && msg instanceof UserMessage) {
                result.add(Map.of("role", "assistant", "content", "I have processed the tool results."));
            }

            if (msg instanceof UserMessage um) {
                Map<String, Object> param = convertUserMessage(um, model);
                if (param != null) result.add(param);
                lastRole = "user";

            } else if (msg instanceof AssistantMessage am) {
                Map<String, Object> param = convertAssistantMessage(am, compat);
                if (param != null) result.add(param);
                lastRole = "assistant";

            } else if (msg instanceof ToolResultMessage trm) {
                // 收集连续的 toolResult 消息
                int j = i;
                while (j < transformed.size() && transformed.get(j) instanceof ToolResultMessage) {
                    ToolResultMessage toolMsg = (ToolResultMessage) transformed.get(j);
                    Map<String, Object> toolResult = new LinkedHashMap<>();
                    toolResult.put("role", "tool");
                    String textResult = extractTextContent(toolMsg);
                    toolResult.put("content", UnicodeSanitizer.sanitizeSurrogates(
                            textResult.isEmpty() ? "(no content)" : textResult));
                    toolResult.put("tool_call_id", toolMsg.toolCallId());
                    if (compat.requiresToolResultName && toolMsg.toolName() != null) {
                        toolResult.put("name", toolMsg.toolName());
                    }
                    result.add(toolResult);
                    j++;
                }
                i = j - 1;
                lastRole = "toolResult";
            }
        }

        return result;
    }

    private Map<String, Object> convertUserMessage(UserMessage um, Model model) {
        Object content = um.content();
        if (content instanceof String text) {
            return Map.of("role", "user", "content", UnicodeSanitizer.sanitizeSurrogates(text));
        }
        if (content instanceof List<?> blocks) {
            List<Map<String, Object>> parts = new ArrayList<>();
            for (Object block : blocks) {
                if (block instanceof TextContent tc) {
                    parts.add(Map.of("type", "text", "text", UnicodeSanitizer.sanitizeSurrogates(tc.text())));
                } else if (block instanceof ImageContent ic) {
                    if (model.input() != null && model.input().contains("image")) {
                        parts.add(Map.of("type", "image_url",
                                "image_url", Map.of("url", "data:" + ic.mimeType() + ";base64," + ic.data())));
                    }
                }
            }
            if (parts.isEmpty()) return null;
            return Map.of("role", "user", "content", parts);
        }
        return null;
    }

    private Map<String, Object> convertAssistantMessage(AssistantMessage am, ResolvedCompat compat) {
        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");

        // 文本内容
        StringBuilder textBuilder = new StringBuilder();
        for (AssistantContentBlock block : am.getContent()) {
            if (block instanceof TextContent tc && tc.text() != null && !tc.text().trim().isEmpty()) {
                textBuilder.append(UnicodeSanitizer.sanitizeSurrogates(tc.text()));
            }
        }

        // thinking 块处理
        List<ThinkingContent> thinkingBlocks = new ArrayList<>();
        for (AssistantContentBlock block : am.getContent()) {
            if (block instanceof ThinkingContent tc && tc.thinking() != null && !tc.thinking().trim().isEmpty()) {
                thinkingBlocks.add(tc);
            }
        }
        if (!thinkingBlocks.isEmpty() && compat.requiresThinkingAsText) {
            StringBuilder thinkingText = new StringBuilder();
            for (ThinkingContent tc : thinkingBlocks) {
                if (thinkingText.length() > 0) thinkingText.append("\n\n");
                thinkingText.append(tc.thinking());
            }
            textBuilder.insert(0, thinkingText);
        }

        if (textBuilder.length() > 0) {
            assistantMsg.put("content", textBuilder.toString());
        }

        // 工具调用
        List<ToolCall> toolCalls = new ArrayList<>();
        for (AssistantContentBlock block : am.getContent()) {
            if (block instanceof ToolCall tc) toolCalls.add(tc);
        }
        if (!toolCalls.isEmpty()) {
            List<Map<String, Object>> tcList = new ArrayList<>();
            for (ToolCall tc : toolCalls) {
                tcList.add(Map.of(
                        "id", tc.id(),
                        "type", "function",
                        "function", Map.of(
                                "name", tc.name(),
                                "arguments", toJson(tc.arguments()))));
            }
            assistantMsg.put("tool_calls", tcList);
        }

        // 跳过空 assistant 消息
        if (!assistantMsg.containsKey("content") && !assistantMsg.containsKey("tool_calls")) {
            return null;
        }

        return assistantMsg;
    }

    private List<Map<String, Object>> convertTools(List<Tool> tools, ResolvedCompat compat) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Tool tool : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            if (tool.parameters() != null) {
                fn.put("parameters", PiAiJson.MAPPER.convertValue(tool.parameters(), Map.class));
            }
            if (compat.supportsStrictMode) {
                fn.put("strict", false);
            }
            result.add(Map.of("type", "function", "function", fn));
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
                                   AssistantMessage output, Model model, ResolvedCompat compat) {
        // 当前正在构建的内容块
        String currentBlockType = null; // "text", "thinking", "toolCall"
        StringBuilder partialArgs = new StringBuilder();

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
                log.debug("跳过无法解析的 SSE 数据: {}", sseEvent.data());
                continue;
            }

            // usage
            Map<String, Object> usage = (Map<String, Object>) chunk.get("usage");
            if (usage != null) {
                output.setUsage(parseChunkUsage(usage, model));
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
            if (choices == null || choices.isEmpty()) continue;
            Map<String, Object> choice = choices.get(0);

            // fallback usage
            if (usage == null && choice.get("usage") instanceof Map<?,?> choiceUsage) {
                output.setUsage(parseChunkUsage((Map<String, Object>) choiceUsage, model));
            }

            // finish_reason
            Object finishReason = choice.get("finish_reason");
            if (finishReason instanceof String fr) {
                output.setStopReason(mapStopReason(fr));
            }

            Map<String, Object> delta = (Map<String, Object>) choice.get("delta");
            if (delta == null) continue;

            // 文本内容
            String content = (String) delta.get("content");
            if (content != null && !content.isEmpty()) {
                if (!"text".equals(currentBlockType)) {
                    finishCurrentBlock(currentBlockType, output, stream, partialArgs);
                    currentBlockType = "text";
                    output.getContent().add(new TextContent("text", "", null));
                    stream.push(new AssistantMessageEvent.TextStart(
                            output.getContent().size() - 1, output));
                }
                int idx = output.getContent().size() - 1;
                TextContent tc = (TextContent) output.getContent().get(idx);
                output.getContent().set(idx, new TextContent("text", tc.text() + content, null));
                stream.push(new AssistantMessageEvent.TextDelta(idx, content, output));
            }

            // reasoning 内容（多种字段名）
            String reasoning = findReasoningDelta(delta);
            if (reasoning != null && !reasoning.isEmpty()) {
                if (!"thinking".equals(currentBlockType)) {
                    finishCurrentBlock(currentBlockType, output, stream, partialArgs);
                    currentBlockType = "thinking";
                    output.getContent().add(new ThinkingContent("thinking", "", null, null));
                    stream.push(new AssistantMessageEvent.ThinkingStart(
                            output.getContent().size() - 1, output));
                }
                int idx = output.getContent().size() - 1;
                ThinkingContent tc = (ThinkingContent) output.getContent().get(idx);
                output.getContent().set(idx, new ThinkingContent("thinking",
                        tc.thinking() + reasoning, tc.thinkingSignature(), tc.redacted()));
                stream.push(new AssistantMessageEvent.ThinkingDelta(idx, reasoning, output));
            }

            // 工具调用
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) delta.get("tool_calls");
            if (toolCalls != null) {
                for (Map<String, Object> tc : toolCalls) {
                    String tcId = (String) tc.get("id");
                    Map<String, Object> fn = (Map<String, Object>) tc.get("function");

                    // 新的 toolCall 开始
                    if (tcId != null && (!"toolCall".equals(currentBlockType)
                            || !tcId.equals(getCurrentToolCallId(output)))) {
                        finishCurrentBlock(currentBlockType, output, stream, partialArgs);
                        currentBlockType = "toolCall";
                        partialArgs.setLength(0);
                        String name = fn != null ? (String) fn.get("name") : "";
                        output.getContent().add(new ToolCall("toolCall", tcId,
                                name != null ? name : "", Map.of(), null));
                        stream.push(new AssistantMessageEvent.ToolCallStart(
                                output.getContent().size() - 1, output));
                    }

                    // 累积参数
                    if (fn != null && fn.get("arguments") instanceof String args) {
                        partialArgs.append(args);
                        int idx = output.getContent().size() - 1;
                        ToolCall current = (ToolCall) output.getContent().get(idx);
                        Map<String, Object> parsed = StreamingJsonParser.parseStreamingJson(partialArgs.toString());
                        output.getContent().set(idx, new ToolCall("toolCall", current.id(),
                                current.name(), parsed, current.thoughtSignature()));
                        stream.push(new AssistantMessageEvent.ToolCallDelta(idx, args, output));
                    }

                    // 更新名称
                    if (fn != null && fn.get("name") instanceof String name && !name.isEmpty()) {
                        int idx = output.getContent().size() - 1;
                        ToolCall current = (ToolCall) output.getContent().get(idx);
                        output.getContent().set(idx, new ToolCall("toolCall", current.id(),
                                name, current.arguments(), current.thoughtSignature()));
                    }
                }
            }
        }

        // 结束最后一个块
        finishCurrentBlock(currentBlockType, output, stream, partialArgs);
    }

    private void finishCurrentBlock(String blockType, AssistantMessage output,
                                     AssistantMessageEventStream stream, StringBuilder partialArgs) {
        if (blockType == null || output.getContent().isEmpty()) return;
        int idx = output.getContent().size() - 1;
        AssistantContentBlock block = output.getContent().get(idx);

        switch (blockType) {
            case "text" -> {
                if (block instanceof TextContent tc) {
                    stream.push(new AssistantMessageEvent.TextEnd(idx, tc.text(), output));
                }
            }
            case "thinking" -> {
                if (block instanceof ThinkingContent tc) {
                    stream.push(new AssistantMessageEvent.ThinkingEnd(idx, tc.thinking(), output));
                }
            }
            case "toolCall" -> {
                if (block instanceof ToolCall tc) {
                    if (partialArgs.length() > 0) {
                        Map<String, Object> args = StreamingJsonParser.parseStreamingJson(partialArgs.toString());
                        ToolCall finalTc = new ToolCall("toolCall", tc.id(), tc.name(), args, tc.thoughtSignature());
                        output.getContent().set(idx, finalTc);
                        stream.push(new AssistantMessageEvent.ToolCallEnd(idx, finalTc, output));
                    } else {
                        stream.push(new AssistantMessageEvent.ToolCallEnd(idx, tc, output));
                    }
                    partialArgs.setLength(0);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String findReasoningDelta(Map<String, Object> delta) {
        for (String field : List.of("reasoning_content", "reasoning", "reasoning_text")) {
            Object val = delta.get(field);
            if (val instanceof String s && !s.isEmpty()) return s;
        }
        return null;
    }

    private String getCurrentToolCallId(AssistantMessage output) {
        if (output.getContent().isEmpty()) return null;
        AssistantContentBlock last = output.getContent().get(output.getContent().size() - 1);
        return (last instanceof ToolCall tc) ? tc.id() : null;
    }

    @SuppressWarnings("unchecked")
    private Usage parseChunkUsage(Map<String, Object> rawUsage, Model model) {
        int promptTokens = getInt(rawUsage, "prompt_tokens", 0);
        int completionTokens = getInt(rawUsage, "completion_tokens", 0);

        int cachedTokens = 0;
        Object promptDetails = rawUsage.get("prompt_tokens_details");
        if (promptDetails instanceof Map<?,?> pd) {
            cachedTokens = getInt((Map<String, Object>) pd, "cached_tokens", 0);
        }

        int reasoningTokens = 0;
        Object completionDetails = rawUsage.get("completion_tokens_details");
        if (completionDetails instanceof Map<?,?> cd) {
            reasoningTokens = getInt((Map<String, Object>) cd, "reasoning_tokens", 0);
        }

        int input = promptTokens - cachedTokens;
        int output = completionTokens + reasoningTokens;
        int total = input + output + cachedTokens;

        Usage usage = new Usage(input, output, cachedTokens, 0, total, null);
        Usage.Cost cost = ModelRegistry.calculateCost(model, usage);
        return new Usage(input, output, cachedTokens, 0, total, cost);
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultValue;
    }

    // ========== 工具方法 ==========

    static StopReason mapStopReason(String reason) {
        if (reason == null) return StopReason.STOP;
        return switch (reason) {
            case "stop", "end" -> StopReason.STOP;
            case "length" -> StopReason.LENGTH;
            case "function_call", "tool_calls" -> StopReason.TOOL_USE;
            case "content_filter" -> StopReason.ERROR;
            default -> StopReason.STOP;
        };
    }

    static String normalizeToolCallId(String id, Model model) {
        if (id == null) return id;
        // OpenAI Responses API 的 pipe-separated ID
        if (id.contains("|")) {
            String callId = id.split("\\|")[0];
            return callId.replaceAll("[^a-zA-Z0-9_-]", "_").substring(0, Math.min(callId.length(), 40));
        }
        if ("openai".equals(model.provider())) {
            return id.length() > 40 ? id.substring(0, 40) : id;
        }
        return id;
    }

    private String mapReasoningEffort(ThinkingLevel level, Map<ThinkingLevel, String> effortMap) {
        if (effortMap != null && effortMap.containsKey(level)) {
            return effortMap.get(level);
        }
        return level.name().toLowerCase();
    }

    private ThinkingLevel getReasoningEffortFromMetadata(StreamOptions options) {
        if (options == null || options.getMetadata() == null) return null;
        Object val = options.getMetadata().get("_reasoning_effort");
        return (val instanceof ThinkingLevel tl) ? tl : null;
    }

    // ========== 兼容性检测 ==========

    /** 已解析的兼容性配置（所有字段非 null） */
    record ResolvedCompat(
            boolean supportsStore,
            boolean supportsDeveloperRole,
            boolean supportsReasoningEffort,
            Map<ThinkingLevel, String> reasoningEffortMap,
            boolean supportsUsageInStreaming,
            String maxTokensField,
            boolean requiresToolResultName,
            boolean requiresAssistantAfterToolResult,
            boolean requiresThinkingAsText,
            String thinkingFormat,
            boolean supportsStrictMode
    ) {}

    static ResolvedCompat getCompat(Model model) {
        ResolvedCompat detected = detectCompat(model);
        OpenAICompletionsCompat compat = model.compat();
        if (compat == null) return detected;

        return new ResolvedCompat(
                compat.supportsStore() != null ? compat.supportsStore() : detected.supportsStore,
                compat.supportsDeveloperRole() != null ? compat.supportsDeveloperRole() : detected.supportsDeveloperRole,
                compat.supportsReasoningEffort() != null ? compat.supportsReasoningEffort() : detected.supportsReasoningEffort,
                compat.reasoningEffortMap() != null ? compat.reasoningEffortMap() : detected.reasoningEffortMap,
                compat.supportsUsageInStreaming() != null ? compat.supportsUsageInStreaming() : detected.supportsUsageInStreaming,
                compat.maxTokensField() != null ? compat.maxTokensField() : detected.maxTokensField,
                compat.requiresToolResultName() != null ? compat.requiresToolResultName() : detected.requiresToolResultName,
                compat.requiresAssistantAfterToolResult() != null ? compat.requiresAssistantAfterToolResult() : detected.requiresAssistantAfterToolResult,
                compat.requiresThinkingAsText() != null ? compat.requiresThinkingAsText() : detected.requiresThinkingAsText,
                compat.thinkingFormat() != null ? compat.thinkingFormat() : detected.thinkingFormat,
                compat.supportsStrictMode() != null ? compat.supportsStrictMode() : detected.supportsStrictMode
        );
    }

    static ResolvedCompat detectCompat(Model model) {
        String provider = model.provider();
        String baseUrl = model.baseUrl() != null ? model.baseUrl() : "";

        boolean isZai = "zai".equals(provider) || baseUrl.contains("api.z.ai");
        boolean isNonStandard = "cerebras".equals(provider) || baseUrl.contains("cerebras.ai")
                || "xai".equals(provider) || baseUrl.contains("api.x.ai")
                || baseUrl.contains("chutes.ai") || baseUrl.contains("deepseek.com")
                || isZai || "opencode".equals(provider) || baseUrl.contains("opencode.ai");
        boolean useMaxTokens = baseUrl.contains("chutes.ai");
        boolean isGrok = "xai".equals(provider) || baseUrl.contains("api.x.ai");

        return new ResolvedCompat(
                !isNonStandard,           // supportsStore
                !isNonStandard,           // supportsDeveloperRole
                !isGrok && !isZai,        // supportsReasoningEffort
                Map.of(),                 // reasoningEffortMap
                true,                     // supportsUsageInStreaming
                useMaxTokens ? "max_tokens" : "max_completion_tokens",
                false,                    // requiresToolResultName
                false,                    // requiresAssistantAfterToolResult
                false,                    // requiresThinkingAsText
                isZai ? "zai" : "openai", // thinkingFormat
                true                      // supportsStrictMode
        );
    }
}
