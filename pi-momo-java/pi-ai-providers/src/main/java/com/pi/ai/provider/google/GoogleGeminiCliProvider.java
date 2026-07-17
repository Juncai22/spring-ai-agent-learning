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
 * Google Gemini CLI / Cloud Code Assist Provider（google-gemini-cli）——Cloud Code Assist 专用 Provider。
 *
 * <p>本类实现了与 Google Cloud Code Assist API 的集成，使用 Cloud Code Assist 内部端点
 * （{@code cloudcode-pa.googleapis.com}）。此 API 是 Google Cloud Code Assist 插件使用的
 * 内部端点，支持多种模型（包括 Gemini 和 Claude 系列模型）。
 *
 * <h3>特征</h3>
 * <ul>
 *   <li>使用 Cloud Code Assist API 端点（不同于公共 Gemini API）</li>
 *   <li>支持 Gemini 和 Claude 模型（通过 Cloud Code Assist 接口）</li>
 *   <li>需要 OAuth 认证（access token + project ID）</li>
 *   <li>API Key 格式为 JSON 编码的凭据：{@code {"token": "...", "projectId": "..."}}</li>
 *   <li>支持会话管理（sessionId）</li>
 *   <li>支持 thinking 配置（自适应和基于预算）</li>
 *   <li>支持工具调用（function calling）</li>
 * </ul>
 *
 * <h3>认证方式</h3>
 * <p>与公共 Gemini API 不同，Cloud Code Assist API 使用 OAuth 2.0 认证：
 * <ol>
 *   <li>调用方需要提供 OAuth access token 和 Google Cloud project ID</li>
 *   <li>凭据以 JSON 格式通过 apiKey 参数传入</li>
 *   <li>请求使用 {@code Authorization: Bearer} 头传递 access token</li>
 * </ol>
 *
 * <h3>请求格式</h3>
 * <p>请求体包含 project、model、request（包含 contents、generationConfig、tools 等），
 * 以及 userAgent 和 requestId 用于追踪。
 *
 * @see GoogleShared
 */
public class GoogleGeminiCliProvider extends BaseProvider implements ApiProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleGeminiCliProvider.class);
    private static final String API_ID = "google-gemini-cli";
    private static final String DEFAULT_ENDPOINT = "https://cloudcode-pa.googleapis.com";
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
                String apiKeyRaw = options != null ? options.getApiKey() : null;
                if (apiKeyRaw == null || apiKeyRaw.isEmpty()) {
                    throw new RuntimeException(
                            "Google Cloud Code Assist requires OAuth authentication.");
                }

                // apiKey is JSON-encoded: { token, projectId }
                Map<String, Object> creds = parseJson(apiKeyRaw);
                String accessToken = (String) creds.get("token");
                String projectId = (String) creds.get("projectId");
                if (accessToken == null || projectId == null) {
                    throw new RuntimeException("Missing token or projectId in credentials.");
                }

                String baseUrl = model.baseUrl() != null ? model.baseUrl().trim() : DEFAULT_ENDPOINT;
                String url = baseUrl + "/v1internal:streamGenerateContent?alt=sse";

                Map<String, Object> requestBody = buildRequest(model, context, projectId, options);
                String body = toJson(requestBody);

                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Authorization", "Bearer " + accessToken);
                headers.put("Content-Type", "application/json");
                headers.put("Accept", "text/event-stream");
                if (model.headers() != null) headers.putAll(model.headers());
                if (options != null && options.getHeaders() != null) headers.putAll(options.getHeaders());

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
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "Google Cloud Code Assist requires OAuth authentication.");
        }

        StreamOptions base = SimpleOptions.buildBaseOptions(model, options, apiKey);
        ThinkingLevel reasoning = options != null ? options.getReasoning() : null;

        if (reasoning == null) {
            return streamWithThinking(model, context, base, false, null, null);
        }

        ThinkingLevel clamped = SimpleOptions.clampReasoning(reasoning);
        if (clamped == null) clamped = reasoning;

        if (GoogleShared.isGemini3Model(model.id())) {
            GoogleShared.GoogleThinkingLevel level = GoogleShared.getGemini3ThinkingLevel(clamped, model.id());
            return streamWithThinking(model, context, base, true, level, null);
        }

        // Budget-based thinking for older models
        ThinkingBudgets customBudgets = options != null ? options.getThinkingBudgets() : null;
        Map<ThinkingLevel, Integer> defaultBudgets = Map.of(
                ThinkingLevel.MINIMAL, 1024, ThinkingLevel.LOW, 2048,
                ThinkingLevel.MEDIUM, 8192, ThinkingLevel.HIGH, 16384);
        int budget = customBudgets != null ? GoogleShared.getGoogleBudget(model.id(), clamped, customBudgets)
                : defaultBudgets.getOrDefault(clamped, 8192);

        int minOutputTokens = 1024;
        int maxTokens = Math.min((base.getMaxTokens() != null ? base.getMaxTokens() : 0) + budget, model.maxTokens());
        if (maxTokens <= budget) {
            budget = Math.max(0, maxTokens - minOutputTokens);
        }

        StreamOptions adapted = StreamOptions.builder()
                .temperature(base.getTemperature())
                .maxTokens(maxTokens)
                .apiKey(base.getApiKey())
                .cacheRetention(base.getCacheRetention())
                .sessionId(base.getSessionId())
                .headers(base.getHeaders())
                .transport(base.getTransport())
                .maxRetryDelayMs(base.getMaxRetryDelayMs())
                .signal(base.getSignal())
                .metadata(thinkingMeta(true, null, budget))
                .build();
        return stream(model, context, adapted);
    }

    private AssistantMessageEventStream streamWithThinking(Model model, Context context,
                                                            StreamOptions base, boolean enabled,
                                                            GoogleShared.GoogleThinkingLevel level,
                                                            Integer budget) {
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
                .metadata(thinkingMeta(enabled, level, budget))
                .build();
        return stream(model, context, adapted);
    }

    private Map<String, Object> thinkingMeta(boolean enabled,
                                              GoogleShared.GoogleThinkingLevel level,
                                              Integer budget) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("_thinking_enabled", enabled);
        if (level != null) meta.put("_thinking_level", level.name());
        if (budget != null) meta.put("_thinking_budget", budget);
        return meta;
    }

    // ========== Request building ==========

    private Map<String, Object> buildRequest(Model model, Context context,
                                              String projectId, StreamOptions options) {
        List<Map<String, Object>> contents = GoogleShared.convertMessages(model, context);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("contents", contents);

        if (options != null && options.getSessionId() != null) {
            request.put("sessionId", options.getSessionId());
        }

        // System instruction
        if (context.systemPrompt() != null && !context.systemPrompt().isEmpty()) {
            request.put("systemInstruction", Map.of(
                    "parts", List.of(Map.of("text",
                            UnicodeSanitizer.sanitizeSurrogates(context.systemPrompt())))));
        }

        // Generation config
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

        if (!generationConfig.isEmpty()) {
            request.put("generationConfig", generationConfig);
        }

        // Tools
        if (context.tools() != null && !context.tools().isEmpty()) {
            boolean useParameters = model.id().startsWith("claude-");
            List<Map<String, Object>> tools = GoogleShared.convertTools(context.tools(), useParameters);
            if (tools != null) request.put("tools", tools);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project", projectId);
        body.put("model", model.id());
        body.put("request", request);
        body.put("userAgent", "pi-coding-agent");
        body.put("requestId", "pi-" + System.currentTimeMillis() + "-"
                + Long.toString(System.nanoTime(), 36).substring(0, 9));

        return body;
    }

    // ========== SSE processing ==========

    @SuppressWarnings("unchecked")
    private void processSseStream(InputStream inputStream, AssistantMessageEventStream stream,
                                   AssistantMessage output, Model model) {
        String currentBlockType = null;

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

            // Unwrap the response wrapper
            Map<String, Object> responseData = (Map<String, Object>) chunk.get("response");
            if (responseData == null) responseData = chunk;

            List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseData.get("candidates");
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

                String finishReason = (String) candidate.get("finishReason");
                if (finishReason != null) {
                    output.setStopReason(GoogleShared.mapStopReasonString(finishReason));
                    if (output.getContent().stream().anyMatch(b -> b instanceof ToolCall)) {
                        output.setStopReason(StopReason.TOOL_USE);
                    }
                }
            }

            Map<String, Object> usageMetadata = (Map<String, Object>) responseData.get("usageMetadata");
            if (usageMetadata != null) {
                updateUsage(output, usageMetadata, model);
            }
        }

        finishCurrentBlock(currentBlockType, output, stream);
    }

    @SuppressWarnings("unchecked")
    private String processPart(Map<String, Object> part, AssistantMessage output,
                                AssistantMessageEventStream stream, String currentBlockType) {
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

        if (part.containsKey("functionCall")) {
            finishCurrentBlock(currentBlockType, output, stream);
            currentBlockType = null;

            Map<String, Object> fc = (Map<String, Object>) part.get("functionCall");
            String name = (String) fc.get("name");
            Map<String, Object> args = (Map<String, Object>) fc.get("args");
            String providedId = (String) fc.get("id");
            String thoughtSignature = (String) part.get("thoughtSignature");

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
        int cachedTokens = getInt(usageMetadata, "cachedContentTokenCount", 0);
        int candidatesTokens = getInt(usageMetadata, "candidatesTokenCount", 0);
        int thoughtsTokens = getInt(usageMetadata, "thoughtsTokenCount", 0);
        int totalTokens = getInt(usageMetadata, "totalTokenCount", 0);

        int input = promptTokens - cachedTokens;
        int outputTokens = candidatesTokens + thoughtsTokens;
        Usage usage = new Usage(input, outputTokens, cachedTokens, 0, totalTokens, null);
        Usage.Cost cost = ModelRegistry.calculateCost(model, usage);
        output.setUsage(new Usage(input, outputTokens, cachedTokens, 0, totalTokens, cost));
    }

    private int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultValue;
    }

    private boolean isThinkingEnabled(StreamOptions options) {
        if (options == null || options.getMetadata() == null) return false;
        return Boolean.TRUE.equals(options.getMetadata().get("_thinking_enabled"));
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
