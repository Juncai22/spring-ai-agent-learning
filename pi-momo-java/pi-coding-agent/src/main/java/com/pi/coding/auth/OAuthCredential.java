package com.pi.coding.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.Objects;

/**
 * OAuth 2.0 认证凭证记录，封装了访问令牌、刷新令牌和过期时间等完整信息。
 *
 * <p>相比 ApiKeyCredential 的静态密钥方式，OAuth 2.0 提供了更安全的令牌管理机制：
 * 访问令牌（accessToken）有有效期限制，过期后可使用刷新令牌（refreshToken）自动获取新的访问令牌，
 * 无需用户重新授权。第三方数据（extra）字段可用于存储提供商返回的额外信息。
 *
 * <p>使用 {@link JsonInclude} 注解标记不序列化 null 值字段，减少存储空间。
 * 通过 {@link JsonCreator} 注解支持 Jackson 反序列化，从持久化存储中恢复凭证。
 *
 * <p>提供了两个关键的状态判断方法：
 * <ul>
 *   <li>{@link #isExpired()} - 判断令牌是否已过期</li>
 *   <li>{@link #isExpiringSoon(long)} - 判断令牌是否即将在指定缓冲时间内过期</li>
 * </ul>
 *
 * @param accessToken  OAuth 访问令牌，用于 API 请求的身份认证
 * @param refreshToken OAuth 刷新令牌，用于在访问令牌过期后获取新的令牌
 * @param expiresAt    访问令牌的过期时间戳（自纪元起的毫秒数）
 * @param extra        OAuth 提供商返回的额外可选数据，如用户信息、令牌范围等
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OAuthCredential(
    String accessToken,
    String refreshToken,
    long expiresAt,
    Map<String, Object> extra
) implements AuthCredential {
    
    @JsonCreator
    public OAuthCredential(
        @JsonProperty("accessToken") String accessToken,
        @JsonProperty("refreshToken") String refreshToken,
        @JsonProperty("expiresAt") long expiresAt,
        @JsonProperty("extra") Map<String, Object> extra
    ) {
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken must not be null");
        this.refreshToken = Objects.requireNonNull(refreshToken, "refreshToken must not be null");
        this.expiresAt = expiresAt;
        this.extra = extra;  // nullable
    }
    
    @Override
    public String type() {
        return "oauth";
    }
    
    /**
     * 检查当前访问令牌是否已经过期。
     *
     * <p>通过比较当前系统时间与过期时间戳来判断。如果当前时间大于等于过期时间戳，则视为已过期。
     * 此方法用于在发起 API 请求前判断是否需要先刷新令牌，避免使用无效令牌导致请求失败。
     *
     * @return 如果令牌已过期返回 true，否则返回 false
     */
    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }
    
    /**
     * 检查当前访问令牌是否将在指定的缓冲时间内过期。
     *
     * <p>此方法用于提前预判令牌是否即将过期，以便在令牌真正过期前就发起刷新操作，
     * 避免在 API 调用过程中出现令牌过期导致的认证失败。建议在每次 API 调用前使用此方法
     * 配合合理的缓冲时间（如 5 分钟）进行预检查，确保令牌的持续有效性。
     *
     * @param bufferMs 缓冲时间（毫秒），用于提前触发令牌刷新的时间窗口
     * @return 如果令牌将在指定缓冲时间内过期返回 true，否则返回 false
     */
    public boolean isExpiringSoon(long bufferMs) {
        return System.currentTimeMillis() + bufferMs >= expiresAt;
    }
    
    /**
     * 使用新的访问令牌和过期时间创建一个更新的 OAuth 凭证。
     *
     * <p>此方法是一个不可变操作，不会修改当前凭证对象，而是返回一个新的 OAuthCredential 实例。
     * 新的凭证会保留原凭证的刷新令牌（refreshToken）和额外数据（extra），
     * 仅替换访问令牌和过期时间。这在令牌刷新流程中非常有用：
     * 刷新成功后，用新令牌和过期时间创建更新后的凭证，同时保留刷新令牌以便后续再次刷新。
     *
     * @param newAccessToken 新的访问令牌
     * @param newExpiresAt   新的过期时间戳（自纪元起的毫秒数）
     * @return 包含更新后值的新 OAuthCredential 实例
     */
    public OAuthCredential withRefreshedToken(String newAccessToken, long newExpiresAt) {
        return new OAuthCredential(newAccessToken, this.refreshToken, newExpiresAt, this.extra);
    }
}
