package com.pi.ai.oauth.google;

import com.pi.ai.oauth.spi.OAuthCredentials;
import com.pi.ai.oauth.spi.OAuthLoginCallbacks;
import com.pi.ai.oauth.spi.OAuthProviderInterface;

import java.util.concurrent.CompletableFuture;

/**
 * Google Antigravity OAuth 认证服务提供商，用于访问 Google Cloud 上的 Antigravity 服务。
 *
 * <p>Antigravity 是 Google Cloud 上的一项服务，提供对多种 AI 模型的访问，包括 Gemini 3、
 * Claude 和 GPT-OSS 等。该 Provider 使用 Google Cloud 的 OAuth 2.0 认证流程获取访问权限。
 *
 * <p>认证流程说明：
 * <ol>
 *   <li>启动本地回调服务器，用于接收 OAuth 授权回调</li>
 *   <li>打开浏览器引导用户访问 Google Cloud OAuth 授权页面</li>
 *   <li>用户在 Google 页面完成登录和授权确认</li>
 *   <li>Google 通过回调地址返回授权码</li>
 *   <li>使用授权码交换访问令牌和刷新令牌，同时获取 projectId 等额外信息</li>
 * </ol>
 *
 * <p>与 Anthropic 和 OpenAI Codex Provider 不同，Antigravity 的 {@link #getApiKey(OAuthCredentials)}
 * 返回的是一个 JSON 字符串，包含 token 和 projectId 两个字段，用于 Antigravity 服务的鉴权。
 *
 * <p>当前实现为骨架代码，完整的登录和令牌刷新逻辑待后续实现。
 */
public class AntigravityOAuthProvider implements OAuthProviderInterface {

    /** Provider 唯一标识符，用于在注册表中标识此 Provider */
    public static final String ID = "google-antigravity";

    @Override
    public String id() { return ID; }

    @Override
    public String name() { return "Google Antigravity"; }

    /**
     * 当前 Provider 使用本地回调服务器来接收 OAuth 授权回调。
     *
     * @return {@code true}，表示需要启动本地 HTTP 服务器
     */
    @Override
    public boolean usesCallbackServer() { return true; }

    /**
     * 执行 Google Antigravity OAuth 登录流程。
     * <p>当前实现尚未完成，抛出 {@link UnsupportedOperationException}。
     *
     * @param callbacks 登录流程的回调接口，用于与用户交互，不可为 null
     * @return 包含 OAuth 凭证的 CompletableFuture
     * @throws UnsupportedOperationException 当前未实现完整的登录逻辑
     */
    @Override
    public CompletableFuture<OAuthCredentials> login(OAuthLoginCallbacks callbacks) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Antigravity OAuth login requires browser interaction"));
    }

    /**
     * 刷新 Google Antigravity 的过期 OAuth 凭证。
     * <p>使用 refresh_token 向 Google 令牌端点申请新的访问令牌。
     * 当前实现尚未完成，抛出 {@link UnsupportedOperationException}。
     *
     * @param credentials 当前已过期的凭证，其中应包含有效的 refresh_token，不可为 null
     * @return 包含更新后凭证的 CompletableFuture
     * @throws UnsupportedOperationException 当前未实现令牌刷新逻辑
     */
    @Override
    public CompletableFuture<OAuthCredentials> refreshToken(OAuthCredentials credentials) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Antigravity OAuth refresh not yet implemented"));
    }

    /**
     * 将 OAuth 凭证转换为 API Key 字符串。
     * <p>Antigravity 的 API Key 是一个 JSON 格式的字符串，包含 access_token 和 projectId 两个字段，
     * 用于 Antigravity 服务的 API 鉴权。projectId 从凭证的额外字段中获取。
     *
     * @param credentials 有效的 OAuth 凭证，其中 extra 字段应包含 "projectId" 键值，不可为 null
     * @return JSON 格式的 API Key 字符串，格式为 {"token":"...","projectId":"..."}
     */
    @Override
    public String getApiKey(OAuthCredentials credentials) {
        Object projectId = credentials.getExtra().get("projectId");
        return "{\"token\":\"" + credentials.getAccess() + "\",\"projectId\":\""
                + (projectId != null ? projectId : "") + "\"}";
    }
}