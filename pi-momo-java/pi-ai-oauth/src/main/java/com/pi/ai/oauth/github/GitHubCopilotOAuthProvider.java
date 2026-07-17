package com.pi.ai.oauth.github;

import com.pi.ai.oauth.spi.OAuthCredentials;
import com.pi.ai.oauth.spi.OAuthLoginCallbacks;
import com.pi.ai.oauth.spi.OAuthProviderInterface;

import java.util.concurrent.CompletableFuture;

/**
 * GitHub Copilot OAuth 认证服务提供商，使用设备码认证流程（Device Code Flow）。
 *
 * <p>该 Provider 实现 {@link OAuthProviderInterface} 接口，采用 GitHub 的 OAuth 2.0 设备码认证流程，
 * 适用于无法直接打开浏览器的 CLI 环境。用户通过在另一设备（如手机或电脑）上访问验证 URL
 * 并输入设备码来完成认证。
 *
 * <p>设备码认证流程说明：
 * <ol>
 *   <li>向 GitHub 设备码端点请求设备码和用户码</li>
 *   <li>向用户展示验证 URL 和用户码</li>
 *   <li>用户在其他设备上访问验证 URL，输入用户码并完成 GitHub 登录授权</li>
 *   <li>客户端轮询令牌端点，等待用户完成授权</li>
 *   <li>授权成功后获取访问令牌和刷新令牌</li>
 * </ol>
 *
 * <p>当前实现为骨架代码，完整的登录和令牌刷新逻辑待后续实现。
 * 使用 {@link #usesCallbackServer()} 返回 {@code false}，表明不需要本地回调服务器。
 */
public class GitHubCopilotOAuthProvider implements OAuthProviderInterface {

    /** Provider 唯一标识符，用于在注册表中标识此 Provider */
    public static final String ID = "github-copilot";

    @Override
    public String id() { return ID; }

    @Override
    public String name() { return "GitHub Copilot"; }

    /**
     * 执行 GitHub Copilot 设备码登录流程。
     *
     * <p>完整的设备码流程包括：
     * <ol>
     *   <li>向 GitHub 设备码端点发起 POST 请求，获取 device_code、user_code 和 verification_uri</li>
     *   <li>通过回调向用户展示 verification_uri 和 user_code，引导用户访问并输入</li>
     *   <li>以轮询方式向 GitHub 令牌端点查询授权状态</li>
     *   <li>用户完成授权后，获取访问令牌和刷新令牌</li>
     * </ol>
     *
     * <p>当前实现尚未完成，抛出 {@link UnsupportedOperationException}。
     *
     * @param callbacks 登录流程的回调接口，用于与用户交互，不可为 null
     * @return 包含 OAuth 凭证的 CompletableFuture
     * @throws UnsupportedOperationException 当前未实现完整的登录逻辑
     */
    @Override
    public CompletableFuture<OAuthCredentials> login(OAuthLoginCallbacks callbacks) {
        // GitHub 设备码流程：
        // 1. 从 GitHub 请求设备码
        // 2. 向用户展示验证 URL 和用户码
        // 3. 轮询令牌端点等待用户完成授权
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("GitHub Copilot OAuth login requires device code flow"));
    }

    /**
     * 刷新 GitHub Copilot 的过期 OAuth 凭证。
     * <p>使用 refresh_token 向 GitHub 令牌端点申请新的访问令牌。
     * 当前实现尚未完成，抛出 {@link UnsupportedOperationException}。
     *
     * @param credentials 当前已过期的凭证，其中应包含有效的 refresh_token，不可为 null
     * @return 包含更新后凭证的 CompletableFuture
     * @throws UnsupportedOperationException 当前未实现令牌刷新逻辑
     */
    @Override
    public CompletableFuture<OAuthCredentials> refreshToken(OAuthCredentials credentials) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("GitHub Copilot OAuth refresh not yet implemented"));
    }

    /**
     * 将 OAuth 凭证转换为 API Key 字符串。
     * <p>GitHub Copilot 的 API Key 直接使用 access_token 作为 Bearer Token。
     *
     * @param credentials 有效的 OAuth 凭证，不可为 null
     * @return access_token 字符串，用于 GitHub Copilot API 调用鉴权
     */
    @Override
    public String getApiKey(OAuthCredentials credentials) {
        return credentials.getAccess();
    }
}