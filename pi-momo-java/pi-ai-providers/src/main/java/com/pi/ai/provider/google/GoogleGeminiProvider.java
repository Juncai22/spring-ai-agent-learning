package com.pi.ai.provider.google;

import com.pi.ai.core.event.AssistantMessageEvent;
import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.registry.ApiProvider;
import com.pi.ai.core.registry.ModelRegistry;
import com.pi.ai.core.types.*;
import com.pi.ai.core.util.*;
import com.pi.ai.provider.common.BaseProvider;
import com.pi.ai.provider.common.SseParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Google Gemini Provider（google-generative-ai）——对接 Google Gemini REST API 的实现。
 *
 * <p>本类实现了与 Google Generative AI REST API 的集成，使用 SSE 流式方式调用
 * Gemini 模型。支持 Gemini 1.5 Pro、Gemini 2.0 Flash、Gemini 2.5 Pro 等模型。
 *
 * <h3>支持的 Google Gemini 特性</h3>
 * <ul>
 *   <li><b>SSE 流式消息生成</b>：通过 {@code streamGenerateContent} 端点流式返回</li>
 *   <li><b>Thinking（思考）</b>：支持 Gemini 2.5 的 thinking 模式和 thought signature</li>
 *   <li><b>Function Calling</b>：支持函数调用，自动生成唯一 ToolCall ID</li>
 *   <li><b>多模态输入</b>：支持文本和图片的混合输入</li>
 *   <li><b>System Instruction</b>：支持系统提示指令</li>
 *   <li><b>Generation Config</b>：支持 temperature、maxOutputTokens 等配置</li>
 *   <li><b>上下文缓存</b>：支持 cachedContent 用量统计</li>
 * </ul>
 *
 * <h3>API 端点</h3>
 * <p>使用 Google Generative Language API 的 streamGenerateContent 端点：
 * <pre>{@code
 * https://generativelanguage.googleapis.com/v1beta/models/{modelId}:streamGenerateContent?alt=sse&key={apiKey}
 * }</pre>
 *
 * <h3>消息格式</h3>
 * <p>Gemini API 使用 "contents" 数组格式，每个 content 包含 role 和 parts：
 * <ul>
 *   <li>user 角色：用户消息，包含文本和图片 parts</li>
 *   <li>model 角色：模型响应，包含文本、thinking 和 functionCall parts</li>
 *   <li>functionResponse：工具结果，包含 functionResponse part</li>
 *   <li>systemInstruction：系统提示，单独参数</li>
 * </ul>
 *
 * <p>思考内容使用 {@code thought: true} 标记，支持 {@code thoughtSignature} 用于跨轮连续性。
 *
 * @see GoogleShared
 * @see GoogleVertexProvider
 */
public class GoogleGeminiProvider extends BaseProvider implements ApiProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleGeminiProvider.class);
    private static final String API_ID = "google-generative-ai";
    private static final AtomicLong TOOL_CALL_COUNTER = new AtomicLong(0);

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
                String baseUrl = model.baseUrl() != null ? model.baseUrl()
                        : "https://generativelanguage.googleapis.com/v1beta";
                String url = baseUrl + "/models/" + model.id() + ":streamGenerateContent?alt=sse&key=" + apiKey;

                Map<String, String> headers = buildHeaders(model, options);
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
        String apiKey = options != null ? options.getApiKey() : null;
        if (apiKey == null || apiKey.isEmpty()) apiKey = EnvApiKeys.getEnvApiKey(model.provider());
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("No API key for provider: " + model.provider());
        }

        StreamOptions base = SimpleOptions.buildBaseOptions(model, options, apiKey);
        ThinkingLevel reasoning = options != null ? options.getReasoning() : null;

        if (reasoning == null) {
            // No reasoning - disable thinking
            return streamWithThinking(model, context, base, false, null, null);
        }

        ThinkingLevel clamped = SimpleOptions.clampReasoning(reasoning);
        if (clamped == null) clamped = reasoning;

        if (GoogleShared.isGemini3ProModel(model.id()) || GoogleShared.isGemini3FlashModel(model.id())) {
            GoogleShared.GoogleThinkingLevel level = GoogleShared.getGemini3ThinkingLevel(clamped, model.id());
            return streamWithThinking(model, context, base, true, level, null);
        }

        ThinkingBudgets customBudgets = options != null ? options.getThinkingBudgets() : null;
        int budget = GoogleShared.getGoogleBudget(model.id(), clamped, customBudgets);
        return streamWithThinking(model, context, base, true, null, budget);
    }

    /**
     * 带 thinking 配置的 stream 调用。
     */
    private AssistantMessageEventStream streamWithThinking(Model model, Context context,
                                                            StreamOptions base, boolean thinkingEnabled,
                                                            GoogleShared.GoogleThinkingLevel level,
                                                            Integer budgetTokens) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (base.getMetadata() != null) meta.putAll(base.getMetadata());
        meta.put("_thinking_enabled", thinkingEnabled);
        if (level != null) meta.put("_thinking_level", level.name());
        if (budgetTokens != null) meta.put("_thinking_budget", budgetTokens);

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

    protected String resolveApiKey(Model model, StreamOptions options) {
        String apiKey = options != null ? options.getApiKey() : null;
        if (apiKey == null || apiKey.isEmpty()) apiKey = EnvApiKeys.getEnvApiKey(model.provider());
        return apiKey != null ? apiKey : "";
    }

    protected Map<String, String> buildHeaders(Model model, StreamOptions options) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (model.headers() != null) headers.putAll(model.headers());
        if (options != null && options.getHeaders() != null) headers.putAll(options.getHeaders());
        return headers;
    }

    protected Map<String, Object> buildParams(Model model, Context context, StreamOptions options) {
        List<Map<String, Object>> contents = GoogleShared.convertMessages(model, context);

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        if (options != null && options.getTemperature() != null) {
            generationConfig.put("temperature", options.getTemperature());
        }
        if (options != null && options.getMaxTokens() != null) {
            generationConfig.put("maxOutputTokens", options.getMaxTokens());
        }

        // Thinking config
        boolean thinkingEnabled = isThinkingEnabled(options);
        if (thinkingEnabled && model.reasoning()) {
            Map<String, Object> thinkingConfig = new LinkedHashMap<>();
            thinkingConfig.put("includeThoughts", true);
            String levelStr = getThinkingLevel(options);
            if (levelStr != null) {
                thinkingConfig.put("thinkingLevel", levelStr);
            } else {
                Integer budget = getThinkingBudget(options);
                if (budget != null) {
                    thinkingConfig.put("thinkingBudget", budget);
                }
            }
            generationConfig.put("thinkingConfig", thinkingConfig);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("contents", contents);

        if (!generationConfig.isEmpty()) {
            params.put("generationConfig", generationConfig);
        }

        // System instruction
        if (context.systemPrompt() != null && !context.systemPrompt().isEmpty()) {
            params.put("systemInstruction", UnicodeSanitizer.sanitizeSurrogates(context.systemPrompt()));
        }

        // Tools
        if (context.tools() != null && !context.tools().isEmpty()) {
            List<Map<String, Object>> tools = GoogleShared.convertTools(context.tools(), false);
            if (tools != null) params.put("tools", tools);
        }

        return params;
    }

    // ========== SSE 事件处理 ==========

    @SuppressWarnings("unchecked")
    protected void processSseStream(InputStream inputStream, AssistantMessageEventStream stream,
                                     AssistantMessage output, Model model) {
        String currentBlockType = null; // "text" or "thinking"

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

            List<Map<String, Object>> candidates = (List<Map<String, Object>>) chunk.get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> candidate = candidates.get(0);
                Map<String, Object> content = (Map<String, Object>) candidate.get("content");
                if (content != null) {
                    List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                    if (parts != null) {
                        for (Map<String, Object> part : parts) {
                            currentBlockType = processPart(part, output, stream, currentBlockType);
                        }
                    }
                }

                // finishReason
                String finishReason = (String) candidate.get("finishReason");
                if (finishReason != null) {
                    output.setStopReason(GoogleShared.mapStopReasonString(finishReason));
                    if (output.getContent().stream().anyMatch(b -> b instanceof ToolCall)) {
                        output.setStopReason(StopReason.TOOL_USE);
                    }
                }
            }

            // usageMetadata
            Map<String, Object> usageMetadata = (Map<String, Object>) chunk.get("usageMetadata");
            if (usageMetadata != null) {
                updateUsage(output, usageMetadata, model);
            }
        }

        // Finish last block
        finishCurrentBlock(currentBlockType, output, stream);
    }

    @SuppressWarnings("unchecked")
    private String processPart(Map<String, Object> part, AssistantMessage output,
                                AssistantMessageEventStream stream, String currentBlockType) {
        // Text or thinking part
        if (part.containsKey("text")) {
            String text = (String) part.get("text");
            boolean isThinking = GoogleShared.isThinkingPart((Boolean) part.get("thought"));
            String thoughtSignature = (String) part.get("thoughtSignature");

            if (isThinking) {
                if (!"thinking".equals(currentBlockType)) {
                    finishCurrentBlock(currentBlockType, output, stream);
                    output.getContent().add(new ThinkingContent("thinking", "", null, null));
                    stream.push(new AssistantMessageEvent.ThinkingStart(
                            output.getContent().size() - 1, output));
                    currentBlockType = "thinking";
                }
                int idx = output.getContent().size() - 1;
                ThinkingContent tc = (ThinkingContent) output.getContent().get(idx);
                String newSig = GoogleShared.retainThoughtSignature(tc.thinkingSignature(), thoughtSignature);
                output.getContent().set(idx, new ThinkingContent("thinking",
                        tc.thinking() + text, newSig, tc.redacted()));
                stream.push(new AssistantMessageEvent.ThinkingDelta(idx, text, output));
            } else {
                if (!"text".equals(currentBlockType)) {
                    finishCurrentBlock(currentBlockType, output, stream);
                    output.getContent().add(new TextContent("text", "", null));
                    stream.push(new AssistantMessageEvent.TextStart(
                            output.getContent().size() - 1, output));
                    currentBlockType = "text";
                }
                int idx = output.getContent().size() - 1;
                TextContent tc = (TextContent) output.getContent().get(idx);
                String newSig = GoogleShared.retainThoughtSignature(tc.textSignature(), thoughtSignature);
                output.getContent().set(idx, new TextContent("text", tc.text() + text, newSig));
                stream.push(new AssistantMessageEvent.TextDelta(idx, text, output));
            }
        }

        // Function call part
        if (part.containsKey("functionCall")) {
            finishCurrentBlock(currentBlockType, output, stream);
            currentBlockType = null;

            Map<String, Object> fc = (Map<String, Object>) part.get("functionCall");
            String name = (String) fc.get("name");
            Map<String, Object> args = (Map<String, Object>) fc.get("args");
            String providedId = (String) fc.get("id");
            String thoughtSignature = (String) part.get("thoughtSignature");

            // Generate unique ID if not provided or duplicate
            boolean needsNewId = providedId == null || providedId.isEmpty()
                    || output.getContent().stream()
                    .anyMatch(b -> b instanceof ToolCall t && providedId.equals(t.id()));
            String toolCallId = needsNewId
                    ? (name != null ? name : "fn") + "_" + System.currentTimeMillis() + "_" + TOOL_CALL_COUNTER.incrementAndGet()
                    : providedId;

            ToolCall toolCall = new ToolCall("toolCall", toolCallId,
                    name != null ? name : "", args != null ? args : Map.of(), thoughtSignature);

            output.getContent().add(toolCall);
            int idx = output.getContent().size() - 1;
            stream.push(new AssistantMessageEvent.ToolCallStart(idx, output));
            stream.push(new AssistantMessageEvent.ToolCallDelta(idx, toJson(toolCall.arguments()), output));
            stream.push(new AssistantMessageEvent.ToolCallEnd(idx, toolCall, output));
        }

        return currentBlockType;
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

    private void updateUsage(AssistantMessage output, Map<String, Object> usageMetadata, Model model) {
        int promptTokens = getInt(usageMetadata, "promptTokenCount", 0);
        int candidatesTokens = getInt(usageMetadata, "candidatesTokenCount", 0);
        int thoughtsTokens = getInt(usageMetadata, "thoughtsTokenCount", 0);
        int cachedTokens = getInt(usageMetadata, "cachedContentTokenCount", 0);
        int totalTokens = getInt(usageMetadata, "totalTokenCount", 0);

        int outputTokens = candidatesTokens + thoughtsTokens;
        Usage usage = new Usage(promptTokens, outputTokens, cachedTokens, 0, totalTokens, null);
        Usage.Cost cost = ModelRegistry.calculateCost(model, usage);
        output.setUsage(new Usage(promptTokens, outputTokens, cachedTokens, 0, totalTokens, cost));
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultValue;
    }

    // ========== Metadata helpers ==========

    private boolean isThinkingEnabled(StreamOptions options) {
        if (options == null || options.getMetadata() == null) return false;
        Object val = options.getMetadata().get("_thinking_enabled");
        return Boolean.TRUE.equals(val);
    }

    private String getThinkingLevel(StreamOptions options) {
        if (options == null || options.getMetadata() == null) return null;
        Object val = options.getMetadata().get("_thinking_level");
        return val instanceof String s ? s : null;
    }

    private Integer getThinkingBudget(StreamOptions options) {
        if (options == null || options.getMetadata() == null) return null;
        Object val = options.getMetadata().get("_thinking_budget");
        return val instanceof Number n ? n.intValue() : null;
    }
}
