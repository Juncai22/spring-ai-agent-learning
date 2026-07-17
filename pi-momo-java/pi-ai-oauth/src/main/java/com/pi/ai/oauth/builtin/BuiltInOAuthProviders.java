package com.pi.ai.oauth.builtin;

import com.pi.ai.oauth.anthropic.AnthropicOAuthProvider;
import com.pi.ai.oauth.github.GitHubCopilotOAuthProvider;
import com.pi.ai.oauth.google.AntigravityOAuthProvider;
import com.pi.ai.oauth.google.GeminiCliOAuthProvider;
import com.pi.ai.oauth.openai.OpenAICodexOAuthProvider;
import com.pi.ai.oauth.registry.OAuthProviderRegistry;

/**
 * 内置 OAuth Provider 的统一注册管理类。
 *
 * <p>该系统目前支持 5 个内置的 OAuth 认证提供商，覆盖主流 AI 平台的 OAuth 认证需求：
 * <ul>
 *   <li>{@link AnthropicOAuthProvider} - Anthropic Claude Pro/Max 订阅认证</li>
 *   <li>{@link GitHubCopilotOAuthProvider} - GitHub Copilot 设备码认证</li>
 *   <li>{@link GeminiCliOAuthProvider} - Google Gemini CLI OAuth 认证</li>
 *   <li>{@link AntigravityOAuthProvider} - Google Antigravity OAuth 认证</li>
 *   <li>{@link OpenAICodexOAuthProvider} - OpenAI Codex ChatGPT Plus/Pro 订阅认证</li>
 * </ul>
 *
 * <p>该类提供批量注册和重置功能，通常在系统启动时调用一次 {@link #registerBuiltInOAuthProviders()}
 * 完成所有内置 Provider 的初始化。
 *
 * <p>对应 pi-mono 前端的 builtInOAuthProviders。
 */
public final class BuiltInOAuthProviders {

    /** 私有构造器，防止实例化 */
    private BuiltInOAuthProviders() {}

    /**
     * 注册全部内置 OAuth Provider。
     *
     * <p>依次创建并注册 5 个内置 OAuth Provider 实例到 {@link OAuthProviderRegistry} 中。
     * 此方法应在应用启动时调用，以确保所有内置认证方式可用。
     *
     * <p>注册的 Provider 包括：
     * <ol>
     *   <li>Anthropic（Claude Pro/Max）</li>
     *   <li>GitHub Copilot</li>
     *   <li>Google Gemini CLI</li>
     *   <li>Google Antigravity</li>
     *   <li>OpenAI Codex（ChatGPT Plus/Pro）</li>
     * </ol>
     */
    public static void registerBuiltInOAuthProviders() {
        OAuthProviderRegistry.registerBuiltIn(new AnthropicOAuthProvider());
        OAuthProviderRegistry.registerBuiltIn(new GitHubCopilotOAuthProvider());
        OAuthProviderRegistry.registerBuiltIn(new GeminiCliOAuthProvider());
        OAuthProviderRegistry.registerBuiltIn(new AntigravityOAuthProvider());
        OAuthProviderRegistry.registerBuiltIn(new OpenAICodexOAuthProvider());
    }

    /**
     * 重置所有 OAuth Provider 为内置默认状态。
     * <p>清空注册表中所有已注册的 Provider（包括自定义 Provider），
     * 然后重新注册所有内置 Provider，使系统恢复到初始状态。
     * <p>此方法委托给 {@link OAuthProviderRegistry#reset()} 实现。
     */
    public static void resetOAuthProviders() {
        OAuthProviderRegistry.reset();
    }
}