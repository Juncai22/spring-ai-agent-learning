package com.pi.ai.provider.common;

import com.pi.ai.core.event.AssistantMessageEvent;
import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.types.*;
import com.pi.ai.core.util.PiAiJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Provider 抽象基类——提供者模式的核心骨架。
 *
 * <p>本类是 pi-momo 框架中所有 AI 模型提供者（Provider）的抽象基类，实现了
 * {@link com.pi.ai.core.registry.ApiProvider} 接口中定义的部分方法（如 {@code stream()} 和
 * {@code streamSimple()} 由子类实现），并为子类提供了一系列通用的基础设施方法。
 *
 * <h3>提供者模式（Provider Pattern）</h3>
 *
 * <p>提供者模式是一种将 API 变化部分与不变部分分离的设计模式。在本框架中：
 * <ul>
 *   <li><b>不变部分（BaseProvider）</b>：HTTP 请求构建、SSE 流解析、重试策略、JSON 序列化、
 *       错误处理、取消信号处理等通用基础设施</li>
 *   <li><b>可变部分（具体 Provider）</b>：消息格式转换、工具调用格式转换、SSE 事件到
 *       内部事件模型的映射、认证方式、端点 URL 构建等与特定 AI API 相关的逻辑</li>
 * </ul>
 *
 * <h3>流式调用流程</h3>
 *
 * <ol>
 *   <li>{@code stream()} 方法被调用，创建 {@link com.pi.ai.core.event.AssistantMessageEventStream}</li>
 *   <li>通过 {@link java.util.concurrent.CompletableFuture#runAsync(Runnable)} 在独立线程中执行 HTTP 请求</li>
 *   <li>构建 HTTP POST 请求（{@link #buildPostRequest(String, String, java.util.Map)}）</li>
 *   <li>发送请求并支持自动重试（{@link #sendWithRetry(java.net.http.HttpRequest, com.pi.ai.core.types.StreamOptions)}）</li>
 *   <li>解析 SSE 事件流，逐步推送到事件流中</li>
 *   <li>请求完成或出错时，结束事件流</li>
 * </ol>
 *
 * <h3>提供的通用能力</h3>
 * <ul>
 *   <li>{@link #HTTP_CLIENT}：共享的 {@link java.net.http.HttpClient} 实例，支持 HTTP/1.1</li>
 *   <li>{@link #buildPostRequest(String, String, java.util.Map)}：构建标准 HTTP POST 请求</li>
 *   <li>{@link #sendWithRetry(java.net.http.HttpRequest, com.pi.ai.core.types.StreamOptions)}：带重试机制的 HTTP 请求发送</li>
 *   <li>{@link #createInitialOutput(com.pi.ai.core.types.Model)}：创建初始的 AssistantMessage 输出对象</li>
 *   <li>{@link #emitError(com.pi.ai.core.event.AssistantMessageEventStream, com.pi.ai.core.types.AssistantMessage, Exception, com.pi.ai.core.types.CancellationSignal)}：发出错误事件</li>
 *   <li>{@link #mergeHeaders(java.util.Map[])}：合并多个 HTTP 头 Map</li>
 *   <li>{@link #toJson(Object)} / {@link #parseJson(String)}：JSON 序列化与反序列化</li>
 * </ul>
 *
 * <h3>子类实现要求</h3>
 * 每个具体 Provider 子类需要实现：
 * <ul>
 *   <li>{@code api()}：返回唯一的 API 标识</li>
 *   <li>{@code stream(Model, Context, StreamOptions)}：核心流式调用方法</li>
 *   <li>{@code streamSimple(Model, Context, SimpleStreamOptions)}：简化版流式调用</li>
 *   <li>消息转换逻辑：将内部消息模型转换为目标 API 的请求格式</li>
 *   <li>SSE 事件处理：将 API 返回的 SSE 事件映射为内部事件模型</li>
 * </ul>
 *
 * @see com.pi.ai.core.registry.ApiProvider
 * @see com.pi.ai.core.event.AssistantMessageEventStream
 * @see com.pi.ai.core.types.AssistantMessage
 */
public abstract class BaseProvider {

    private static final Logger log = LoggerFactory.getLogger(BaseProvider.class);

    /** 共享 HttpClient 实例，支持 HTTP/1.1 协议和自动重定向。
     *
     * 使用单例模式共享同一个 HttpClient 实例以复用连接池，
     * 设置 30 秒连接超时和正常重定向策略。 */
    protected static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 构建 HTTP POST 请求。
     *
     * <p>构建一个标准的 HTTP POST 请求，自动设置 Content-Type 为 application/json，
     * Accept 为 text/event-stream（SSE 流式响应）。支持通过 headers 参数传入自定义 HTTP 头，
     * 如认证信息（Authorization / x-api-key）、API 版本号、Beta 功能标志等。
     *
     * <p>请求体为 JSON 字符串，通过 {@link java.net.http.HttpRequest.BodyPublishers#ofString(String)}
     * 发布请求体数据。
     *
     * @param url     请求 URL（完整的 API 端点地址）
     * @param body    请求体 JSON 字符串
     * @param headers 自定义 HTTP 头，可为 null。每个子类可以在调用前构建自己的 headers Map，
     *               例如添加 Authorization 头、API 版本号等
     * @return 构建完成的 HttpRequest 实例
     */
    protected HttpRequest buildPostRequest(String url, String body, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream");

        if (headers != null) {
            headers.forEach(builder::header);
        }

        return builder.build();
    }

    /**
     * 发送 HTTP 请求并返回 InputStream 响应，支持自动重试机制。
     *
     * <p>这是核心的 HTTP 请求发送方法，实现了完整的重试策略：
     * <ul>
     *   <li>最多重试 {@link RetryPolicy#MAX_RETRIES} 次（3 次）</li>
     *   <li>可重试的场景：HTTP 429（限流）、5xx（服务端错误）、网络异常（如连接超时、连接拒绝）</li>
     *   <li>重试策略：优先使用服务端返回的 Retry-After 头，否则使用指数退避（1s, 2s, 4s）</li>
     *   <li>支持取消信号：每次重试前检查是否被取消，避免无效重试</li>
     *   <li>支持最大重试延迟限制：如果服务端要求的 Retry-After 超过 maxRetryDelayMs，直接失败</li>
     * </ul>
     *
     * <p>错误处理流程：
     * <ol>
     *   <li>获取 HTTP 响应后检查状态码</li>
     *   <li>2xx 状态码：直接返回成功的响应</li>
     *   <li>可重试状态码（429, 5xx）：计算延迟后等待重试</li>
     *   <li>不可重试状态码：立即抛出异常</li>
     *   <li>网络异常（IOException 等）：判断是否还有重试次数，有则等待后重试</li>
     * </ol>
     *
     * @param request HTTP 请求对象
     * @param options 流式调用选项，包含 maxRetryDelayMs（最大重试延迟限制）和 signal（取消信号）
     * @return HTTP 响应，包含可读取的 InputStream 响应体
     * @throws Exception 请求失败或重试耗尽时抛出
     */
    protected HttpResponse<InputStream> sendWithRetry(HttpRequest request, StreamOptions options) throws Exception {
        CancellationSignal signal = options != null ? options.getSignal() : null;
        Integer maxRetryDelayMs = options != null ? options.getMaxRetryDelayMs() : null;

        Exception lastError = null;

        for (int attempt = 0; attempt <= RetryPolicy.MAX_RETRIES; attempt++) {
            // 检查取消信号
            if (signal != null && signal.isCancelled()) {
                throw new InterruptedException("请求已取消");
            }

            try {
                HttpResponse<InputStream> response = HTTP_CLIENT.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());

                int status = response.statusCode();

                // 成功响应
                if (status >= 200 && status < 300) {
                    return response;
                }

                // 读取错误响应体
                String errorText = readErrorBody(response);

                // 判断是否可重试
                if (attempt < RetryPolicy.MAX_RETRIES && RetryPolicy.isRetryable(status, errorText)) {
                    long retryAfterMs = RetryPolicy.extractRetryAfterMs(response);
                    long delayMs = RetryPolicy.calculateDelay(attempt, retryAfterMs);

                    // 检查延迟是否超限
                    if (retryAfterMs > 0 && RetryPolicy.exceedsMaxDelay(retryAfterMs, maxRetryDelayMs)) {
                        long delaySec = Math.round(retryAfterMs / 1000.0);
                        throw new RuntimeException(
                                "服务端要求等待 " + delaySec + " 秒后重试，超过最大重试延迟限制");
                    }

                    log.debug("HTTP {} 错误，第 {} 次重试，延迟 {}ms", status, attempt + 1, delayMs);
                    RetryPolicy.sleep(delayMs, signal);
                    continue;
                }

                // 不可重试或重试耗尽
                throw new RuntimeException("HTTP " + status + " 错误: " + errorText);

            } catch (RuntimeException | InterruptedException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                // 网络错误可重试
                if (attempt < RetryPolicy.MAX_RETRIES) {
                    long delayMs = RetryPolicy.calculateDelay(attempt, -1);
                    log.debug("网络错误，第 {} 次重试，延迟 {}ms: {}", attempt + 1, delayMs, e.getMessage());
                    RetryPolicy.sleep(delayMs, signal);
                } else {
                    throw new RuntimeException("网络错误: " + e.getMessage(), e);
                }
            }
        }

        throw new RuntimeException("重试耗尽", lastError);
    }

    /**
     * 读取错误响应体文本。
     *
     * <p>从 HTTP 错误响应中读取完整的响应体内容，用于错误日志记录和异常信息构造。
     * 如果读取失败（例如流已关闭或编码问题），返回一个占位字符串。
     *
     * @param response HTTP 响应对象
     * @return 错误响应体的文本内容，读取失败时返回 "(无法读取错误响应体)"
     */
    private String readErrorBody(HttpResponse<InputStream> response) {
        try (InputStream is = response.body()) {
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(无法读取错误响应体)";
        }
    }

    /**
     * 创建初始的 AssistantMessage 输出对象（用于流式累积）。
     *
     * <p>在流式调用开始时创建一个空的 AssistantMessage 对象，包含模型信息、空的
     * 内容列表和零值用量统计。后续 SSE 事件处理过程中会逐步填充内容和更新用量。
     *
     * @param model 目标模型，包含 api、provider、model id 等信息
     * @return 初始化的 AssistantMessage，内容列表为空，用量为零
     */
    protected AssistantMessage createInitialOutput(Model model) {
        return AssistantMessage.builder()
                .content(new ArrayList<>())
                .api(model.api())
                .provider(model.provider())
                .model(model.id())
                .usage(new Usage(0, 0, 0, 0, 0,
                        new Usage.Cost(0, 0, 0, 0, 0)))
                .stopReason(StopReason.STOP)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 发出错误事件并结束流。
     *
     * <p>当流式调用过程中发生异常时，调用此方法：
     * <ul>
     *   <li>如果取消信号已触发，设置 stopReason 为 ABORTED（用户主动取消）</li>
     *   <li>否则设置 stopReason 为 ERROR（服务端错误或网络异常）</li>
     *   <li>将异常信息设置到 output 的 errorMessage 中</li>
     *   <li>推送 Error 事件到事件流</li>
     *   <li>结束事件流</li>
     * </ul>
     *
     * @param stream  事件流，用于推送错误事件和结束流
     * @param output  当前累积的 AssistantMessage 对象
     * @param error   捕获的异常
     * @param signal  取消信号（可为 null），用于判断是否为用户主动取消
     */
    protected void emitError(AssistantMessageEventStream stream, AssistantMessage output,
                             Exception error, CancellationSignal signal) {
        StopReason reason = (signal != null && signal.isCancelled())
                ? StopReason.ABORTED
                : StopReason.ERROR;
        output.setStopReason(reason);
        output.setErrorMessage(error.getMessage());
        stream.push(new AssistantMessageEvent.Error(reason, output));
        stream.end(null);
    }

    /**
     * 合并多个 headers Map，后面的覆盖前面的。
     *
     * <p>用于将多个来源的 HTTP 头合并到一个 Map 中，后面的参数会覆盖前面相同 key 的值。
     * 常用于合并模型默认头、Provider 默认头、以及调用方自定义头。
     *
     * @param headerSources 多个 headers Map，按优先级从低到高排列
     * @return 合并后的 headers Map
     */
    @SafeVarargs
    protected final Map<String, String> mergeHeaders(Map<String, String>... headerSources) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Map<String, String> source : headerSources) {
            if (source != null) {
                merged.putAll(source);
            }
        }
        return merged;
    }

    /**
     * 将 Java 对象序列化为 JSON 字符串。
     *
     * <p>使用 Jackson ObjectMapper 将 Java 对象序列化为 JSON 字符串。
     * 用于构建 HTTP 请求体。如果序列化失败，抛出运行时异常。
     *
     * @param obj 要序列化的 Java 对象
     * @return JSON 字符串
     * @throws RuntimeException 如果序列化过程中发生异常
     */
    protected String toJson(Object obj) {
        try {
            return PiAiJson.MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    /**
     * 将 JSON 字符串解析为 Map。
     *
     * <p>使用 Jackson ObjectMapper 将 JSON 字符串解析为 {@code Map<String, Object>}。
     * 用于解析 SSE 事件中的 JSON 数据。如果解析失败，抛出运行时异常。
     *
     * @param json 要解析的 JSON 字符串
     * @return 解析后的 Map 对象
     * @throws RuntimeException 如果 JSON 格式无效或解析过程中发生异常
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> parseJson(String json) {
        try {
            return PiAiJson.MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("JSON 解析失败: " + json, e);
        }
    }
}
