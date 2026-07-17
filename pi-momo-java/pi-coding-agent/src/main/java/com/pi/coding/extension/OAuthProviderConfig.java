package com.pi.coding.extension;

import com.pi.ai.core.types.Model;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * OAuth 提供者配置 —— 用于支持 /login 登录的 OAuth 认证配置。
 *
 * <p>定义了一个 OAuth 提供者的完整配置，包括：
 * <ul>
 *   <li>显示名称：在登录 UI 中展示</li>
 *   <li>登录处理器：执行 OAuth 登录流程</li>
 *   <li>刷新处理器：刷新过期的凭据</li>
 *   <li>API Key 转换器：将凭据转换为 API Key 字符串</li>
 *   <li>模型修改器：根据凭据修改模型列表（可选）</li>
 * </ul>
 *
 * @param name           在登录 UI 中显示的提供者名称
 * @param loginHandler   执行登录流程的处理器
 * @param refreshHandler 刷新过期凭据的处理器
 * @param getApiKey      将凭据转换为 API Key 字符串的函数
 * @param modifyModels   根据凭据修改模型列表的可选函数
 */
public record OAuthProviderConfig(
    String name,
    OAuthLoginHandler loginHandler,
    OAuthRefreshHandler refreshHandler,
    OAuthGetApiKeyHandler getApiKey,
    OAuthModifyModelsHandler modifyModels
) {

    /**
     * OAuth 登录流程的处理器。
     */
    @FunctionalInterface
    public interface OAuthLoginHandler {
        /**
         * 执行登录流程。
         *
         * <p>使用回调接口与用户交互，完成 OAuth 认证流程，返回凭据。
         *
         * @param callbacks 登录流程的回调接口
         * @return 一个 CompletableFuture，完成时包含认证凭据
         */
        CompletableFuture<OAuthCredentials> login(OAuthLoginCallbacks callbacks);
    }

    /**
     * OAuth 凭据刷新处理器。
     */
    @FunctionalInterface
    public interface OAuthRefreshHandler {
        /**
         * 刷新过期的凭据。
         *
         * <p>当访问令牌过期时，使用刷新令牌获取新的凭据。
         *
         * @param credentials 当前凭据
         * @return 一个 CompletableFuture，完成时包含更新后的凭据
         */
        CompletableFuture<OAuthCredentials> refresh(OAuthCredentials credentials);
    }

    /**
     * 凭据转 API Key 的处理器。
     */
    @FunctionalInterface
    public interface OAuthGetApiKeyHandler {
        /**
         * 将凭据转换为 API Key 字符串。
         *
         * <p>OAuth 登录完成后，需要将凭据转换为 API Key 字符串供后续 API 调用使用。
         *
         * @param credentials 认证凭据
         * @return API Key 字符串
         */
        String getApiKey(OAuthCredentials credentials);
    }

    /**
     * 根据凭据修改模型列表的处理器。
     */
    @FunctionalInterface
    public interface OAuthModifyModelsHandler {
        /**
         * 修改此提供者的模型列表。
         *
         * <p>根据 OAuth 凭据动态调整可用的模型列表，例如添加或筛选特定模型。
         *
         * @param models      要修改的模型列表
         * @param credentials 认证凭据
         * @return 修改后的模型列表
         */
        List<Model> modifyModels(List<Model> models, OAuthCredentials credentials);
    }

    /**
     * OAuthProviderConfig 的构建器。
     */
    public static class Builder {
        private String name;
        private OAuthLoginHandler loginHandler;
        private OAuthRefreshHandler refreshHandler;
        private OAuthGetApiKeyHandler getApiKey;
        private OAuthModifyModelsHandler modifyModels;

        public Builder name(String name) { this.name = name; return this; }

        public Builder loginHandler(OAuthLoginHandler loginHandler) { this.loginHandler = loginHandler; return this; }

        public Builder refreshHandler(OAuthRefreshHandler refreshHandler) { this.refreshHandler = refreshHandler; return this; }

        public Builder getApiKey(OAuthGetApiKeyHandler getApiKey) { this.getApiKey = getApiKey; return this; }

        public Builder modifyModels(OAuthModifyModelsHandler modifyModels) { this.modifyModels = modifyModels; return this; }

        public OAuthProviderConfig build() {
            return new OAuthProviderConfig(name, loginHandler, refreshHandler, getApiKey, modifyModels);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
