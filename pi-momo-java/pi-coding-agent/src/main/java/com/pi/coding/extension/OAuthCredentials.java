package com.pi.coding.extension;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * OAuth 认证凭据 —— 从登录流程中返回的凭据信息。
 *
 * <p>包含访问令牌、刷新令牌、过期时间和提供者特定的额外数据。
 * 用于支持第三方服务的 OAuth 2.0 认证流程。
 *
 * @param accessToken  访问令牌
 * @param refreshToken 刷新令牌（可为 null）
 * @param expiresAt    过期时间戳（毫秒，可为 null）
 * @param extra        提供者特定的额外数据（可为 null）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OAuthCredentials(
    @JsonProperty("accessToken") String accessToken,
    @JsonProperty("refreshToken") String refreshToken,
    @JsonProperty("expiresAt") Long expiresAt,
    @JsonProperty("extra") Map<String, Object> extra
) {

    /**
     * OAuthCredentials 的构建器。
     */
    public static class Builder {
        private String accessToken;
        private String refreshToken;
        private Long expiresAt;
        private Map<String, Object> extra;

        public Builder accessToken(String accessToken) { this.accessToken = accessToken; return this; }

        public Builder refreshToken(String refreshToken) { this.refreshToken = refreshToken; return this; }

        public Builder expiresAt(Long expiresAt) { this.expiresAt = expiresAt; return this; }

        public Builder extra(Map<String, Object> extra) { this.extra = extra; return this; }

        public OAuthCredentials build() {
            return new OAuthCredentials(accessToken, refreshToken, expiresAt, extra);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
