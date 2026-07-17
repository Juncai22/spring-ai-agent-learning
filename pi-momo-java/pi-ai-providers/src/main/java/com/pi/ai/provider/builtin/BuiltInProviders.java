package com.pi.ai.provider.builtin;

import com.pi.ai.core.registry.ApiProviderRegistry;
import com.pi.ai.provider.anthropic.AnthropicProvider;
import com.pi.ai.provider.bedrock.BedrockProvider;
import com.pi.ai.provider.google.GoogleGeminiCliProvider;
import com.pi.ai.provider.google.GoogleGeminiProvider;
import com.pi.ai.provider.google.GoogleVertexProvider;
import com.pi.ai.provider.mistral.MistralProvider;
import com.pi.ai.provider.openai.AzureOpenAIResponsesProvider;
import com.pi.ai.provider.openai.OpenAICodexResponsesProvider;
import com.pi.ai.provider.openai.OpenAICompletionsProvider;
import com.pi.ai.provider.openai.OpenAIResponsesProvider;

/**
 * 内置 API Provider 注册管理——将所有内置的 AI 模型 Provider 注册到框架中。
 *
 * <p>本类在框架启动时调用，负责注册全部 10 个内置 API Provider 到
 * {@link com.pi.ai.core.registry.ApiProviderRegistry} 中。每个 Provider 通过唯一的
 * API 标识符（如 "anthropic-messages"、"openai-responses"、"google-generative-ai" 等）
 * 注册，后续框架通过此标识符查找对应的 Provider 实例。
 *
 * <h3>注册的 Provider 列表</h3>
 * <ol>
 *   <li>{@link AnthropicProvider} - Anthropic Messages API (anthropic-messages)</li>
 *   <li>{@link OpenAICompletionsProvider} - OpenAI Chat Completions API (openai-completions)</li>
 *   <li>{@link OpenAIResponsesProvider} - OpenAI Responses API (openai-responses)</li>
 *   <li>{@link AzureOpenAIResponsesProvider} - Azure OpenAI Responses API (azure-openai-responses)</li>
 *   <li>{@link OpenAICodexResponsesProvider} - OpenAI Codex Responses API (openai-codex-responses)</li>
 *   <li>{@link GoogleGeminiProvider} - Google Gemini API (google-generative-ai)</li>
 *   <li>{@link GoogleGeminiCliProvider} - Google Gemini CLI API (google-gemini-cli)</li>
 *   <li>{@link GoogleVertexProvider} - Google Vertex AI API (google-vertex)</li>
 *   <li>{@link MistralProvider} - Mistral Chat API (mistral-conversations)</li>
 *   <li>{@link BedrockProvider} - Amazon Bedrock API (bedrock-converse-stream)，懒加载 AWS SDK</li>
 * </ol>
 *
 * <h3>Bedrock 懒加载</h3>
 * <p>Bedrock Provider 采用懒加载策略，注册时不会加载 AWS SDK 依赖。实际调用时，
 * {@link BedrockProvider#stream} 方法会通过反射检查 AWS SDK 是否在 classpath 中，
 * 如果不存在则返回错误信息。这样设计是为了避免在不需要使用 Bedrock 时强制引入
 * AWS SDK 依赖。
 *
 * <p>对应 TypeScript 实现中的 register-builtins.ts。
 *
 * @see com.pi.ai.core.registry.ApiProviderRegistry
 * @see com.pi.ai.core.registry.ApiProvider
 */
public final class BuiltInProviders {

    private static final String SOURCE_ID = "builtin";

    private BuiltInProviders() {}

    /**
     * 注册全部内置 API Provider。
     */
    public static void registerBuiltInApiProviders() {
        ApiProviderRegistry.register(new AnthropicProvider(), SOURCE_ID);
        ApiProviderRegistry.register(new OpenAICompletionsProvider(), SOURCE_ID);
        ApiProviderRegistry.register(new OpenAIResponsesProvider(), SOURCE_ID);
        ApiProviderRegistry.register(new AzureOpenAIResponsesProvider(), SOURCE_ID);
        ApiProviderRegistry.register(new OpenAICodexResponsesProvider(), SOURCE_ID);
        ApiProviderRegistry.register(new GoogleGeminiProvider(), SOURCE_ID);
        ApiProviderRegistry.register(new GoogleGeminiCliProvider(), SOURCE_ID);
        ApiProviderRegistry.register(new GoogleVertexProvider(), SOURCE_ID);
        ApiProviderRegistry.register(new MistralProvider(), SOURCE_ID);
        // Bedrock Provider 懒加载：注册时不加载 AWS SDK，首次调用时才检查
        ApiProviderRegistry.register(new BedrockProvider(), SOURCE_ID);
    }

    /**
     * 清空所有 Provider 后重新注册内置 Provider。
     */
    public static void resetApiProviders() {
        ApiProviderRegistry.clear();
        registerBuiltInApiProviders();
    }
}
