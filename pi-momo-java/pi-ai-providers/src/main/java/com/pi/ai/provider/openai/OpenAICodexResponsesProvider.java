package com.pi.ai.provider.openai;

import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.registry.ApiProvider;
import com.pi.ai.core.types.*;
import com.pi.ai.core.util.EnvApiKeys;

/**
 * OpenAI Codex Responses API Provider（openai-codex-responses）——Codex 专用 Provider。
 *
 * <p>本类继承 {@link OpenAIResponsesProvider}，复用其核心的 Responses API 调用逻辑，
 * 仅覆盖 API 标识和认证方式。Codex 是 OpenAI 的代码生成模型，使用独立的 API 端点和
 * 认证密钥体系。
 *
 * <h3>与 OpenAIResponsesProvider 的区别</h3>
 * <ul>
 *   <li>API 标识为 "openai-codex-responses"</li>
 *   <li>优先使用 {@code OPENAI_CODEX_API_KEY} 环境变量</li>
 *   <li>回退到 {@code OPENAI_API_KEY} 环境变量</li>
 *   <li>其他消息转换、SSE 解析等逻辑完全复用父类实现</li>
 * </ul>
 *
 * <p>这种设计模式（继承 + 覆盖）避免了代码重复，同时保持了不同 Provider 间的灵活性。
 *
 * @see OpenAIResponsesProvider
 */
public class OpenAICodexResponsesProvider extends OpenAIResponsesProvider implements ApiProvider {

    private static final String API_ID = "openai-codex-responses";

    @Override
    public String api() {
        return API_ID;
    }

    @Override
    public AssistantMessageEventStream streamSimple(Model model, Context context, SimpleStreamOptions options) {
        String apiKey = options != null ? options.getApiKey() : null;
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = EnvApiKeys.getEnvApiKey("openai-codex");
        }
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = EnvApiKeys.getEnvApiKey(model.provider());
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("No API key for provider: " + model.provider());
        }

        StreamOptions base = com.pi.ai.core.util.SimpleOptions.buildBaseOptions(model, options, apiKey);
        return stream(model, context, base);
    }
}
