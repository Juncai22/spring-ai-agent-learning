package com.pi.ai.oauth.spi;

import com.pi.ai.core.types.Model;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * OAuth 认证服务提供商的 SPI（服务提供者接口）。
 *
 * <p>该接口定义了 OAuth 2.0 认证流程的核心抽象，所有 OAuth 认证提供商（如 Anthropic、GitHub Copilot、
 * Google Gemini CLI、Google Antigravity、OpenAI Codex 等）都必须实现此接口，以提供统一的认证能力。
 *
 * <p>主要职责包括：
 * <ul>
 *   <li>执行 OAuth 登录流程，获取访问凭证</li>
 *   <li>刷新已过期的凭证，延长访问时效</li>
 *   <li>将凭证转换为 API Key 字符串，供下游 SDK 使用</li>
 *   <li>可选地修改模型列表（如更新 baseUrl 等信息）</li>
 * </ul>
 *
 * <p>对应 pi-mono 前端的 OAuthProviderInterface 接口。
 */
public interface OAuthProviderInterface {

    /**
     * 获取当前 OAuth Provider 的唯一标识符。
     * <p>该标识符用于在注册表中区分不同的 Provider，例如 "anthropic"、"github-copilot" 等。
     *
     * @return 唯一标识符字符串，不可为 null
     */
    String id();

    /**
     * 获取当前 OAuth Provider 的人类可读显示名称。
     * <p>用于在 UI 界面中展示给用户，例如 "Anthropic (Claude Pro/Max)"、"GitHub Copilot" 等。
     *
     * @return 显示名称字符串，不可为 null
     */
    String name();

    /**
     * 执行 OAuth 登录流程，异步获取认证凭证。
     *
     * <p>不同的 Provider 可能采用不同的 OAuth 流程：
     * <ul>
     *   <li>授权码流程（Authorization Code Flow）：通过浏览器重定向获取授权码，再交换为 Token</li>
     *   <li>设备码流程（Device Code Flow）：用户通过设备码在另一设备上完成认证</li>
     *   <li>PKCE 流程：在授权码流程基础上增加 code_verifier/code_challenge 安全增强</li>
     * </ul>
     *
     * <p>在登录过程中，通过 {@link OAuthLoginCallbacks} 与用户交互，例如打开浏览器、
     * 显示提示信息、等待用户输入授权码等。
     *
     * @param callbacks 登录流程的回调接口，用于与用户交互，不可为 null
     * @return 包含访问凭证的 CompletableFuture，登录成功时返回有效凭证，失败时异常结束
     */
    CompletableFuture<OAuthCredentials> login(OAuthLoginCallbacks callbacks);

    /**
     * 判断当前 Provider 是否使用本地回调服务器。
     *
     * <p>如果返回 {@code true}，表示 Provider 使用授权码流程且需要启动本地 HTTP 服务器来接收
     * OAuth 授权回调。如果返回 {@code false}，则支持手动输入授权码，或使用设备码等其他流程。
     *
     * <p>默认实现返回 {@code false}，子类可根据需要重写。
     *
     * @return 如果使用本地回调服务器返回 {@code true}，否则返回 {@code false}
     */
    default boolean usesCallbackServer() {
        return false;
    }

    /**
     * 刷新已过期的 OAuth 凭证，返回更新后的新凭证。
     *
     * <p>当凭证过期时（即 {@link OAuthCredentials#isExpired()} 返回 {@code true}），
     * 调用此方法使用 refresh_token 向认证服务器申请新的 access_token。
     *
     * @param credentials 当前已过期的凭证，其中应包含有效的 refresh_token，不可为 null
     * @return 包含更新后凭证的 CompletableFuture，刷新成功时返回新凭证，失败时异常结束
     */
    CompletableFuture<OAuthCredentials> refreshToken(OAuthCredentials credentials);

    /**
     * 将 OAuth 凭证转换为 API Key 字符串。
     * <p>该字符串将直接用于下游 SDK 的 API 调用鉴权，例如作为 HTTP 请求头的 Bearer Token。
     *
     * @param credentials 有效的 OAuth 凭证，不可为 null
     * @return 可用于 API 调用的认证字符串
     */
    String getApiKey(OAuthCredentials credentials);

    /**
     * 可选地修改模型列表，例如更新 baseUrl 等信息。
     *
     * <p>某些 Provider 在获取凭证后，需要根据凭证信息更新模型的访问地址（如添加代理前缀）。
     * 默认实现直接返回原模型列表，不做任何修改。
     *
     * @param models      原始模型列表，不可为 null
     * @param credentials 当前有效的 OAuth 凭证，可用于提取额外信息，不可为 null
     * @return 修改后的模型列表（可能为新的列表实例）
     */
    default List<Model> modifyModels(List<Model> models, OAuthCredentials credentials) {
        return models;
    }
}