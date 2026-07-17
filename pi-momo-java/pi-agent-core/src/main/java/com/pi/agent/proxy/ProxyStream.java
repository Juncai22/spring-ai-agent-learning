package com.pi.agent.proxy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.pi.agent.event.ProxyAssistantMessageEvent;
import com.pi.ai.core.event.AssistantMessageEvent;
import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.types.*;
import com.pi.ai.core.util.PiAiJson;
import com.pi.ai.core.util.StreamingJsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 代理流式请求工具类 —— 将 LLM 调用通过代理服务器转发，而非直接调用 LLM 提供商。
 *
 * <p>该代理方案的核心思路是：客户端将请求发送到代理服务器，由代理服务器负责认证和请求转发，
 * 代理服务器在返回 SSE（Server-Sent Events）流时，会移除 delta 事件中的 partial 字段以节省带宽。
 * 客户端需要根据收到的增量事件，在本地重建完整的 AssistantMessage 对象。
 *
 * <p>对应 TypeScript 侧的 {@code streamProxy} 函数，是 Java 版本的等效实现。
 *
 * <p>覆盖的需求：Requirements 36.1 - 36.15，涵盖代理流式请求的完整生命周期，
 * 包括：请求构建、发送、SSE 解析、事件处理、取消、错误处理、消息重建等。
 *
 * <p>使用示例：
 * <pre>{@code
 * ProxyStreamOptions options = ProxyStreamOptions.proxyBuilder()
 *     .authToken("your-token")
 *     .proxyUrl("https://your-proxy.example.com")
 *     .build();
 *
 * AssistantMessageEventStream stream = ProxyStream.streamProxy(model, context, options);
 * stream.forEach(event -> { ... });
 * }</pre>
 *
 * @see ProxyStreamOptions
 * @see ProxyAssistantMessageEvent
 * @see AssistantMessageEventStream
 */
public final class ProxyStream {

    /** 共享的 HttpClient 实例，所有代理请求复用此实例。连接超时设置为 30 秒。 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /** 私有构造器，防止实例化。此类为纯工具类，所有方法均为静态方法。 */
    private ProxyStream() {
        // Utility class — no instantiation
    }

    /**
     * 通过代理服务器发送流式请求的入口方法（无取消信号版本）。
     *
     * <p>使用此方法作为创建 Agent 时传入的 {@code streamFn} 参数，
     * 即可让 Agent 的所有 LLM 调用都经过代理服务器中转。
     *
     * <p>该方法内部调用带取消信号的重载版本，传入 {@code null} 作为信号，
     * 表示不支持中途取消操作。
     *
     * @param model   需要使用的 LLM 模型实例，包含 api、provider、model id 等信息
     * @param context 当前对话上下文，包含历史消息和系统提示
     * @param options 代理流配置选项，必须包含 authToken 和 proxyUrl
     * @return AssistantMessageEventStream 事件流，可通过订阅该流获取 LLM 的流式响应
     *
     * @see #streamProxy(Model, Context, ProxyStreamOptions, CancellationSignal)
     */
    public static AssistantMessageEventStream streamProxy(
            Model model,
            Context context,
            ProxyStreamOptions options) {

        return streamProxy(model, context, options, null);
    }

    /**
     * 通过代理服务器发送流式请求的入口方法（支持取消信号版本）。
     *
     * <p>使用此方法作为创建 Agent 时传入的 {@code streamFn} 参数，
     * 即可让 Agent 的所有 LLM 调用都经过代理服务器中转。
     *
     * <p>工作流程概述：
     * <ol>
     *   <li>构建 AssistantMessage 骨架对象，用于在客户端逐步重建完整消息</li>
     *   <li>序列化请求体（model + context + options）为 JSON</li>
     *   <li>向代理服务器发送 POST 请求，路径为 {@code {proxyUrl}/api/stream}</li>
     *   <li>逐行读取 SSE 响应流，解析 {@code data: } 前缀行</li>
     *   <li>将每个代理事件转换为标准 AssistantMessageEvent，并推入事件流</li>
     *   <li>读取完毕后结束事件流</li>
     * </ol>
     *
     * @param model   需要使用的 LLM 模型实例，包含 api、provider、model id 等信息
     * @param context 当前对话上下文，包含历史消息和系统提示
     * @param options 代理流配置选项，必须包含 authToken 和 proxyUrl
     * @param signal  可选的取消信号，用于在请求前或读取过程中中止请求
     * @return AssistantMessageEventStream 事件流，可通过订阅该流获取 LLM 的流式响应
     */
    public static AssistantMessageEventStream streamProxy(
            Model model,
            Context context,
            ProxyStreamOptions options,
            CancellationSignal signal) {

        // 创建事件流实例，所有解析出的 AssistantMessageEvent 将推入此流
        var stream = AssistantMessageEventStream.create();

        // 使用 CompletableFuture.runAsync 在异步线程中执行网络请求，避免阻塞调用线程
        CompletableFuture.runAsync(() -> {
            // 初始化 partial 消息 —— 这是客户端本地重建的 AssistantMessage 骨架。
            // 代理服务器在 SSE 事件中去掉了 partial 字段以节省带宽，
            // 客户端需要根据收到的 Start/Delta/End 事件逐步填充此对象。
            AssistantMessage partial = AssistantMessage.builder()
                    .content(new ArrayList<>())          // 内容块列表，初始为空，后续逐步添加
                    .api(model.api())                    // LLM API 类型（如 openai、anthropic 等）
                    .provider(model.provider())          // 服务提供商名称
                    .model(model.id())                   // 模型 ID（如 claude-sonnet-4-20250514）
                    .usage(new Usage(0, 0, 0, 0, 0, new Usage.Cost(0, 0, 0, 0, 0)))  // 初始用量为 0
                    .stopReason(StopReason.STOP)         // 默认停止原因为 STOP
                    .timestamp(System.currentTimeMillis()) // 记录当前时间戳
                    .build();

            // 用于追踪工具调用参数的流式 JSON 构建。
            // 键为 contentIndex（内容块索引），值为逐步累积的 JSON 片段。
            // 工具调用的参数（arguments）是逐步到达的，需要在线解析流式 JSON。
            Map<Integer, StringBuilder> partialJsonMap = new HashMap<>();

            HttpResponse<InputStream> response = null;
            try {
                // ---- 步骤 1: 构建请求体 ----
                // 将 model、context 和 options 序列化为 JSON 字符串
                String requestBody = buildRequestBody(model, context, options);

                // ---- 步骤 2: 构建 HTTP 请求 ----
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(options.getProxyUrl() + "/api/stream"))  // 代理服务器流式端点
                        .header("Authorization", "Bearer " + options.getAuthToken())  // Bearer Token 认证
                        .header("Content-Type", "application/json")                  // JSON 内容类型
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();

                // ---- 步骤 3: 发送前检查取消信号 ----
                // 如果用户已在请求发送前取消，则直接抛出 CancelledException
                if (signal != null && signal.isCancelled()) {
                    throw new CancelledException("Request aborted by user");
                }

                // ---- 步骤 4: 发送请求并获取响应流 ----
                // 使用 BodyHandlers.ofInputStream() 以流式方式读取响应，避免将整个响应体加载到内存
                response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());

                // ---- 步骤 5: 检查响应状态码 ----
                // 非 2xx 状态码视为代理错误，尝试从响应体中解析错误信息
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String errorMessage = parseErrorResponse(response);
                    throw new ProxyException(errorMessage);
                }

                // ---- 步骤 6: 逐行解析 SSE 流 ----
                // SSE（Server-Sent Events）格式：每行以 "data: " 开头，后跟 JSON 数据
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        // 每读取一行都检查取消信号，确保能及时响应用户取消操作
                        if (signal != null && signal.isCancelled()) {
                            throw new CancelledException("Request aborted by user");
                        }

                        // 仅处理以 "data: " 开头的 SSE 数据行，忽略注释和空行
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();  // 去掉 "data: " 前缀并去除首尾空白
                            if (!data.isEmpty()) {
                                // 反序列化为 ProxyAssistantMessageEvent 实例
                                ProxyAssistantMessageEvent proxyEvent = PiAiJson.MAPPER.readValue(
                                        data, ProxyAssistantMessageEvent.class);
                                // 将代理事件转换为标准 AssistantMessageEvent，并更新 partial 消息
                                AssistantMessageEvent event = processProxyEvent(
                                        proxyEvent, partial, partialJsonMap);
                                if (event != null) {
                                    stream.push(event);  // 将事件推入事件流，供订阅者消费
                                }
                            }
                        }
                    }
                }

                // ---- 步骤 7: 读取完成后再次检查取消信号 ----
                if (signal != null && signal.isCancelled()) {
                    throw new CancelledException("Request aborted by user");
                }

                // 标记事件流结束，通知所有订阅者
                stream.end();

            } catch (CancelledException e) {
                // ---- 取消处理 ----
                // 用户取消了请求，将停止原因设为 ABORTED，并在流中推送错误事件
                partial.setStopReason(StopReason.ABORTED);
                partial.setErrorMessage(e.getMessage());
                stream.push(new AssistantMessageEvent.Error(StopReason.ABORTED, partial));
                stream.end();

            } catch (Exception e) {
                // ---- 异常处理 ----
                // 捕获所有其他异常（网络错误、JSON 解析错误、代理错误等），
                // 将停止原因设为 ERROR，并推送包含错误信息的事件
                String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                partial.setStopReason(StopReason.ERROR);
                partial.setErrorMessage(errorMessage);
                stream.push(new AssistantMessageEvent.Error(StopReason.ERROR, partial));
                stream.end();
            }
        });

        return stream;
    }

    /**
     * 构建发送给代理服务器的 JSON 请求体。
     *
     * <p>请求体包含三个部分：
     * <ul>
     *   <li>{@code model} — 模型信息（api、provider、id 等）</li>
     *   <li>{@code context} — 对话上下文（消息列表、系统提示等）</li>
     *   <li>{@code options} — 可选配置项（temperature、maxTokens、reasoning 等），仅当有值时加入</li>
     * </ul>
     *
     * @param model   模型实例
     * @param context 对话上下文
     * @param options 代理流配置选项
     * @return JSON 格式的请求体字符串
     * @throws JsonProcessingException 如果 JSON 序列化失败
     */
    private static String buildRequestBody(Model model, Context context, ProxyStreamOptions options)
            throws JsonProcessingException {

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);       // 模型信息
        body.put("context", context);   // 对话上下文

        // 构建可选的配置项 Map，仅包含非 null 的字段以减小请求体大小
        Map<String, Object> optionsMap = new HashMap<>();
        if (options.getTemperature() != null) {
            optionsMap.put("temperature", options.getTemperature());  // 采样温度
        }
        if (options.getMaxTokens() != null) {
            optionsMap.put("maxTokens", options.getMaxTokens());      // 最大输出 Token 数
        }
        if (options.getReasoning() != null) {
            optionsMap.put("reasoning", options.getReasoning());      // 推理级别
        }
        body.put("options", optionsMap);

        // 使用 Jackson ObjectMapper 将 Map 序列化为 JSON 字符串
        return PiAiJson.MAPPER.writeValueAsString(body);
    }

    /**
     * 解析代理服务器返回的非 2xx 错误响应，提取错误信息。
     *
     * <p>先尝试从响应体的 JSON 中提取 {@code error} 字段，如果解析失败则回退到状态码信息。
     *
     * @param response 非 2xx 的 HTTP 响应
     * @return 人类可读的错误描述字符串
     */
    private static String parseErrorResponse(HttpResponse<InputStream> response) {
        String baseMessage = "Proxy error: " + response.statusCode();
        try (InputStream body = response.body()) {
            if (body != null) {
                // 读取完整响应体
                String responseBody = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                if (!responseBody.isEmpty()) {
                    // 尝试将响应体解析为 JSON Map
                    @SuppressWarnings("unchecked")
                    Map<String, Object> errorData = PiAiJson.MAPPER.readValue(responseBody, Map.class);
                    Object error = errorData.get("error");
                    if (error != null) {
                        return "Proxy error: " + error;  // 返回代理服务器返回的具体错误信息
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败时静默处理，使用默认的状态码信息
        }
        return baseMessage;
    }

    /**
     * 处理单个代理事件，更新本地重建的 partial 消息，并返回对应的标准 AssistantMessageEvent。
     *
     * <p>该方法实现了从代理优化事件到标准事件的状态机转换。
     * 代理服务器将 LLM 的流式响应拆分为多种事件类型（Start、TextStart/Delta/End、
     * ThinkingStart/Delta/End、ToolCallStart/Delta/End、Done、Error），
     * 每种事件需要不同方式更新 partial 消息并生成对应的标准事件。
     *
     * <p><b>事件类型处理逻辑：</b>
     * <ul>
     *   <li>{@code Start} — 流开始，直接转发</li>
     *   <li>{@code TextStart/Delta/End} — 文本内容块，逐步拼接 text 字段</li>
     *   <li>{@code ThinkingStart/Delta/End} — 思考过程内容块，逐步拼接 thinking 字段</li>
     *   <li>{@code ToolCallStart/Delta/End} — 工具调用，逐步解析流式 JSON 参数</li>
     *   <li>{@code Done} — 流正常结束，设置停止原因和用量信息</li>
     *   <li>{@code Error} — 流异常结束，设置停止原因和错误信息</li>
     * </ul>
     *
     * @param proxyEvent     代理事件实例，包含事件类型和增量数据
     * @param partial        正在重建的 AssistantMessage 对象
     * @param partialJsonMap 工具调用参数流式 JSON 追踪 Map，键为 contentIndex
     * @return 对应的标准 AssistantMessageEvent，如果事件类型未识别则返回 null
     */
    static AssistantMessageEvent processProxyEvent(
            ProxyAssistantMessageEvent proxyEvent,
            AssistantMessage partial,
            Map<Integer, StringBuilder> partialJsonMap) {

        // 使用 if-else instanceof 链来处理不同事件类型（兼容 Java 17 语法，不支持 switch 模式匹配）
        // 每个事件类型对应一个 ProxyAssistantMessageEvent 子类

        // ---- 流开始事件 ----
        if (proxyEvent instanceof ProxyAssistantMessageEvent.Start) {
            return new AssistantMessageEvent.Start(partial);
        }

        // ---- 文本内容块开始 ----
        if (proxyEvent instanceof ProxyAssistantMessageEvent.TextStart e) {
            // 确保 content 列表容量足够存放指定索引的内容块
            ensureContentSize(partial, e.contentIndex());
            // 在指定位置创建一个空的 TextContent 占位
            partial.getContent().set(e.contentIndex(), new TextContent("text", "", null));
            return new AssistantMessageEvent.TextStart(e.contentIndex(), partial);
        }

        // ---- 文本内容增量更新 ----
        if (proxyEvent instanceof ProxyAssistantMessageEvent.TextDelta e) {
            AssistantContentBlock content = partial.getContent().get(e.contentIndex());
            if (content instanceof TextContent tc) {
                // TextContent 是 Java record（不可变类），因此需要创建新实例来更新文本
                String newText = tc.text() + e.delta();  // 将 delta 追加到已有文本后
                partial.getContent().set(e.contentIndex(), new TextContent("text", newText, tc.textSignature()));
                return new AssistantMessageEvent.TextDelta(e.contentIndex(), e.delta(), partial);
            }
            throw new IllegalStateException("Received text_delta for non-text content");
        }

        // ---- 文本内容块结束 ----
        if (proxyEvent instanceof ProxyAssistantMessageEvent.TextEnd e) {
            AssistantContentBlock content = partial.getContent().get(e.contentIndex());
            if (content instanceof TextContent tc) {
                // 设置文本签名（contentSignature），用于验证内容完整性
                partial.getContent().set(e.contentIndex(),
                        new TextContent("text", tc.text(), e.contentSignature()));
                return new AssistantMessageEvent.TextEnd(e.contentIndex(), tc.text(), partial);
            }
            throw new IllegalStateException("Received text_end for non-text content");
        }

        // ---- 思考过程内容块开始 ----
        if (proxyEvent instanceof ProxyAssistantMessageEvent.ThinkingStart e) {
            ensureContentSize(partial, e.contentIndex());
            // 在指定位置创建一个空的 ThinkingContent 占位
            partial.getContent().set(e.contentIndex(), new ThinkingContent("thinking", "", null, null));
            return new AssistantMessageEvent.ThinkingStart(e.contentIndex(), partial);
        }

        // ---- 思考过程增量更新 ----
        if (proxyEvent instanceof ProxyAssistantMessageEvent.ThinkingDelta e) {
            AssistantContentBlock content = partial.getContent().get(e.contentIndex());
            if (content instanceof ThinkingContent tc) {
                // 将 delta 追加到已有思考内容后
                String newThinking = tc.thinking() + e.delta();
                partial.getContent().set(e.contentIndex(),
                        new ThinkingContent("thinking", newThinking, tc.thinkingSignature(), tc.redacted()));
                return new AssistantMessageEvent.ThinkingDelta(e.contentIndex(), e.delta(), partial);
            }
            throw new IllegalStateException("Received thinking_delta for non-thinking content");
        }

        // ---- 思考过程内容块结束 ----
        if (proxyEvent instanceof ProxyAssistantMessageEvent.ThinkingEnd e) {
            AssistantContentBlock content = partial.getContent().get(e.contentIndex());
            if (content instanceof ThinkingContent tc) {
                // 设置思考内容签名
                partial.getContent().set(e.contentIndex(),
                        new ThinkingContent("thinking", tc.thinking(), e.contentSignature(), tc.redacted()));
                return new AssistantMessageEvent.ThinkingEnd(e.contentIndex(), tc.thinking(), partial);
            }
            throw new IllegalStateException("Received thinking_end for non-thinking content");
        }

        // ---- 工具调用开始 ----
        if (proxyEvent instanceof ProxyAssistantMessageEvent.ToolCallStart e) {
            ensureContentSize(partial, e.contentIndex());
            // 在指定位置创建 ToolCall 占位，arguments 初始为空 Map
            partial.getContent().set(e.contentIndex(),
                    new ToolCall("toolCall", e.id(), e.toolName(), new HashMap<>(), null));
            // 为该工具调用创建流式 JSON 构建器，用于逐步累积参数 JSON 片段
            partialJsonMap.put(e.contentIndex(), new StringBuilder());
            return new AssistantMessageEvent.ToolCallStart(e.contentIndex(), partial);
        }

        // ---- 工具调用参数增量更新 ----
        if (proxyEvent instanceof ProxyAssistantMessageEvent.ToolCallDelta e) {
            AssistantContentBlock content = partial.getContent().get(e.contentIndex());
            if (content instanceof ToolCall tc) {
                // 获取或创建该索引对应的流式 JSON 构建器
                StringBuilder partialJson = partialJsonMap.computeIfAbsent(
                        e.contentIndex(), k -> new StringBuilder());
                partialJson.append(e.delta());  // 追加 JSON 片段

                // 使用流式 JSON 解析器解析当前累积的 JSON 字符串，
                // 获取已解析的部分参数，用于实时更新 ToolCall 的 arguments
                Map<String, Object> arguments = StreamingJsonParser.parseStreamingJson(partialJson.toString());

                // 使用最新的 arguments 更新 ToolCall
                partial.getContent().set(e.contentIndex(),
                        new ToolCall("toolCall", tc.id(), tc.name(), arguments, tc.thoughtSignature()));

                return new AssistantMessageEvent.ToolCallDelta(e.contentIndex(), e.delta(), partial);
            }
            throw new IllegalStateException("Received toolcall_delta for non-toolCall content");
        }

        // ---- 工具调用结束 ----
        if (proxyEvent instanceof ProxyAssistantMessageEvent.ToolCallEnd e) {
            AssistantContentBlock content = partial.getContent().get(e.contentIndex());
            if (content instanceof ToolCall tc) {
                // 清理该索引的流式 JSON 构建器，释放内存
                partialJsonMap.remove(e.contentIndex());
                return new AssistantMessageEvent.ToolCallEnd(e.contentIndex(), tc, partial);
            }
            // 如果 ToolCall 内容已被其他逻辑处理，则返回 null
            return null;
        }

        // ---- 流正常结束 ----
        if (proxyEvent instanceof ProxyAssistantMessageEvent.Done e) {
            // 设置最终停止原因（如 stop、end_turn、max_tokens 等）
            partial.setStopReason(e.reason());
            // 设置 Token 用量统计
            partial.setUsage(e.usage());
            return new AssistantMessageEvent.Done(e.reason(), partial);
        }

        // ---- 流错误结束 ----
        if (proxyEvent instanceof ProxyAssistantMessageEvent.Error e) {
            // 设置停止原因为错误
            partial.setStopReason(e.reason());
            // 设置错误信息
            partial.setErrorMessage(e.errorMessage());
            // 设置已产生的 Token 用量
            partial.setUsage(e.usage());
            return new AssistantMessageEvent.Error(e.reason(), partial);
        }

        // ---- 未知事件类型 ----
        // 如果代理服务器返回了未识别的事件类型，打印警告日志并跳过
        System.err.println("Unhandled proxy event type: " + proxyEvent.type());
        return null;
    }

    /**
     * 确保 AssistantMessage 的 content 列表容量足够存放指定索引的内容块。
     *
     * <p>由于代理事件是流式到达的，content 列表的索引可能不连续，
     * 此方法在需要时用 {@code null} 填充列表，确保 {@code list.set(index, ...)} 不会越界。
     *
     * @param partial 正在重建的 AssistantMessage 对象
     * @param index   需要确保可达的索引位置
     */
    private static void ensureContentSize(AssistantMessage partial, int index) {
        while (partial.getContent().size() <= index) {
            partial.getContent().add(null);  // 用 null 填充到目标索引
        }
    }

    /**
     * 请求取消异常 —— 当用户在请求发送前或读取过程中取消操作时抛出。
     *
     * <p>该异常会被 {@link #streamProxy(Model, Context, ProxyStreamOptions, CancellationSignal)}
     * 方法捕获并处理为 {@link StopReason#ABORTED} 状态。
     */
    private static class CancelledException extends Exception {
        CancelledException(String message) {
            super(message);
        }
    }

    /**
     * 代理错误异常 —— 当代理服务器返回非 2xx 状态码或发生代理相关错误时抛出。
     *
     * <p>该异常会被 {@link #streamProxy(Model, Context, ProxyStreamOptions, CancellationSignal)}
     * 方法捕获并处理为 {@link StopReason#ERROR} 状态。
     */
    private static class ProxyException extends Exception {
        ProxyException(String message) {
            super(message);
        }
    }
}
