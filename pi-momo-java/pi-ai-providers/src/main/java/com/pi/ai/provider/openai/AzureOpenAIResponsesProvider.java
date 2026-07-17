package com.pi.ai.provider.openai;

import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.registry.ApiProvider;
import com.pi.ai.core.types.*;
import com.pi.ai.core.util.EnvApiKeys;

/**
 * Azure OpenAI Responses API Provider（azure-openai-responses）——Azure 专用 Provider。
 *
 * <p>本类继承 {@link OpenAIResponsesProvider}，复用其核心的 Responses API 调用逻辑，
 * 覆盖认证方式和 URL 构建。Azure OpenAI 服务使用与 OpenAI 相同的 Responses API 格式，
 * 但认证方式不同（使用 api-key 头而非 Bearer token）。
 *
 * <h3>与 OpenAIResponsesProvider 的区别</h3>
 * <ul>
 *   <li>API 标识为 "azure-openai-responses"</li>
 *   <li>认证方式：使用 {@code api-key} HTTP 头而非 {@code Authorization: Bearer}</li>
 *   <li>优先使用 {@code AZURE_OPENAI_API_KEY} 环境变量</li>
 *   <li>URL 构建需要通过 {@code baseUrl} 配置 Azure 的部署端点</li>
 *   <li>其他消息转换、SSE 解析等逻辑完全复用父类实现</li>
 * </ul>
 *
 * <h3>Azure 认证流程</h3>
 * <ol>
 *   <li>获取 API Key（优先从 options 中获取，其次从环境变量获取）</li>
 *   <li>构建自定义 headers，使用 api-key 头传递密钥</li>
 *   <li>调用父类的 stream 方法，传入自定义 headers</li>
 *   <li>父类在发送请求时会将自定义 headers 合并到请求中</li>
 * </ol>
 *
 * @see OpenAIResponsesProvider
 */
public class AzureOpenAIResponsesProvider extends OpenAIResponsesProvider implements ApiProvider {

    private static final String API_ID = "azure-openai-responses";

    @Override
    public String api() {
        return API_ID;
    }

    @Override
    public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
        // 确保使用 Azure 认证方式
        String apiKey = options != null ? options.getApiKey() : null;
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = EnvApiKeys.getEnvApiKey("azure-openai");
        }

        StreamOptions adapted = StreamOptions.builder()
                .temperature(options != null ? options.getTemperature() : null)
                .maxTokens(options != null ? options.getMaxTokens() : null)
                .apiKey(apiKey)
                .cacheRetention(options != null ? options.getCacheRetention() : null)
                .sessionId(options != null ? options.getSessionId() : null)
                .headers(buildAzureHeaders(model, options, apiKey))
                .transport(options != null ? options.getTransport() : null)
                .maxRetryDelayMs(options != null ? options.getMaxRetryDelayMs() : null)
                .signal(options != null ? options.getSignal() : null)
                .metadata(options != null ? options.getMetadata() : null)
                .build();

        return super.stream(model, context, adapted);
    }

    @Override
    public AssistantMessageEventStream streamSimple(Model model, Context context, SimpleStreamOptions options) {
        String apiKey = options != null ? options.getApiKey() : null;
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = EnvApiKeys.getEnvApiKey("azure-openai");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("No API key for provider: azure-openai");
        }

        StreamOptions base = com.pi.ai.core.util.SimpleOptions.buildBaseOptions(model, options, apiKey);
        return stream(model, context, base);
    }

    private java.util.Map<String, String> buildAzureHeaders(Model model, StreamOptions options, String apiKey) {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        // Azure 使用 api-key 头而非 Bearer token
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.put("api-key", apiKey);
        }
        if (model.headers() != null) headers.putAll(model.headers());
        if (options != null && options.getHeaders() != null) headers.putAll(options.getHeaders());
        return headers;
    }
}
