package com.pi.coding.auth;

import java.util.concurrent.CompletableFuture;

/**
 * OAuth 提供商接口，定义了不同 AI 服务提供商的 OAuth 2.0 认证流程规范。
 *
 * <p>每个 AI 服务提供商（如 Anthropic、OpenAI 等）需要实现此接口，
 * 以处理各自特定的 OAuth 认证流程，包括：
 * <ul>
 *   <li>构建授权 URL 并嵌入 CSRF 防护的 state 参数</li>
 *   <li>将授权码交换为访问令牌和刷新令牌</li>
 *   <li>使用刷新令牌自动续期过期的访问令牌</li>
 * </ul>
 *
 * <p>通过 {@link AuthStorage#registerOAuthProvider(OAuthProvider)} 注册后，
 * AuthStorage 可以自动管理 OAuth 登录和令牌刷新流程。
 *
 * @see AuthStorage
 * @see OAuthLoginCallbacks
 */
public interface OAuthProvider {
    
    /**
     * 返回提供商唯一标识 ID。
     *
     * <p>此 ID 用于在 {@link AuthStorage} 中映射凭证和 OAuth 提供商，
     * 必须与凭证存储中使用的提供商 ID 一致。
     *
     * @return 提供商 ID，如 "anthropic"、"openai"
     */
    String providerId();
    
    /**
     * 生成 OAuth 授权流程的授权 URL。
     *
     * <p>该 URL 通常指向服务提供商的授权页面，用户将在浏览器中打开此页面
     * 并授权应用程序访问其账户。state 参数用于防止 CSRF 攻击：
     * 在回调验证时比对 state 值，确保请求来自本应用发起的授权流程。
     *
     * @param state 用于 CSRF 防护的随机 state 参数值，回调时必须验证
     * @return 完整的授权 URL 字符串
     */
    String getAuthorizationUrl(String state);
    
    /**
     * 将授权码交换为访问令牌和刷新令牌，完成 OAuth 授权流程。
     *
     * <p>用户在授权页面同意授权后，服务提供商将回调一个授权码（authorization code）。
     * 此方法使用该授权码向服务提供商的令牌端点发起请求，获取访问令牌和刷新令牌。
     *
     * @param code 从用户授权回调中获取的授权码
     * @return 一个 CompletableFuture，异步返回包含访问令牌和刷新令牌的 OAuthCredential
     */
    CompletableFuture<OAuthCredential> exchangeCode(String code);
    
    /**
     * 使用刷新令牌刷新已过期的访问令牌。
     *
     * <p>当访问令牌过期时，使用 OAuth 2.0 的 refresh_token grant 类型
     * 向服务提供商的令牌端点请求新的访问令牌，无需用户重新授权。
     * 返回的凭证包含新的访问令牌和过期时间，同时保留刷新令牌以便后续再次刷新。
     *
     * @param credential 当前已过期或即将过期的 OAuth 凭证，其中包含刷新令牌
     * @return 一个 CompletableFuture，异步返回刷新后的新 OAuthCredential
     */
    CompletableFuture<OAuthCredential> refreshToken(OAuthCredential credential);
}
