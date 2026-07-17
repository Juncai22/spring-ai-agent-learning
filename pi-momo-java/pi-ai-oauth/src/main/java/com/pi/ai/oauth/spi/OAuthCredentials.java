package com.pi.ai.oauth.spi;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * OAuth 2.0 认证凭证，用于封装 OAuth 流程产生的访问令牌和刷新令牌。
 *
 * <p>该实体包含以下核心字段：
 * <ul>
 *   <li><b>refresh token</b>：用于在访问令牌过期时获取新的访问令牌</li>
 *   <li><b>access token</b>：用于调用受保护 API 的短期令牌</li>
 *   <li><b>过期时间</b>：access token 的过期时间戳（毫秒）</li>
 *   <li><b>额外字段</b>：Provider 特定的附加数据，如 projectId 等</li>
 * </ul>
 *
 * <p>使用 Jackson 注解支持 JSON 序列化/反序列化：
 * <ul>
 *   <li>通过 {@link JsonProperty} 映射核心字段</li>
 *   <li>通过 {@link JsonAnyGetter}/{@link JsonAnySetter} 处理额外动态字段</li>
 * </ul>
 *
 * <p>对应 pi-mono 前端的 OAuthCredentials 类型。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OAuthCredentials {

    /** 刷新令牌，用于在访问令牌过期时获取新的访问令牌 */
    @JsonProperty("refresh")
    private String refresh;

    /** 访问令牌，用于调用受保护 API 的短期凭证 */
    @JsonProperty("access")
    private String access;

    /** 访问令牌的过期时间戳（毫秒），通过 {@link System#currentTimeMillis()} 比较 */
    @JsonProperty("expires")
    private long expires;

    /** 额外动态字段映射表，用于存储 Provider 特定的数据（如 projectId、scope 等） */
    private final Map<String, Object> extra = new LinkedHashMap<>();

    /** 默认无参构造器，用于 Jackson 反序列化 */
    public OAuthCredentials() {}

    /**
     * 全参构造器，创建包含刷新令牌、访问令牌和过期时间的凭证。
     *
     * @param refresh 刷新令牌
     * @param access  访问令牌
     * @param expires 过期时间戳（毫秒）
     */
    public OAuthCredentials(String refresh, String access, long expires) {
        this.refresh = refresh;
        this.access = access;
        this.expires = expires;
    }

    /** @return 刷新令牌 */
    public String getRefresh() { return refresh; }

    /** @param refresh 刷新令牌 */
    public void setRefresh(String refresh) { this.refresh = refresh; }

    /** @return 访问令牌 */
    public String getAccess() { return access; }

    /** @param access 访问令牌 */
    public void setAccess(String access) { this.access = access; }

    /** @return 过期时间戳（毫秒） */
    public long getExpires() { return expires; }

    /** @param expires 过期时间戳（毫秒） */
    public void setExpires(long expires) { this.expires = expires; }

    /**
     * 获取所有额外动态字段（用于 Jackson 序列化）。
     * <p>此方法通过 {@link JsonAnyGetter} 注解被 Jackson 识别，
     * 将 extra 映射中的所有键值对作为 JSON 对象的顶层属性输出。
     *
     * @return 额外字段的不可变视图映射表
     */
    @JsonAnyGetter
    public Map<String, Object> getExtra() { return extra; }

    /**
     * 设置额外动态字段（用于 Jackson 反序列化）。
     * <p>此方法通过 {@link JsonAnySetter} 注解被 Jackson 识别，
     * 在反序列化时将 JSON 中未映射到核心字段的键值对存入 extra 映射表。
     *
     * @param key   字段名称
     * @param value 字段值
     */
    @JsonAnySetter
    public void setExtra(String key, Object value) { extra.put(key, value); }

    /**
     * 判断当前凭证是否已过期。
     * <p>比较当前系统时间与 {@link #expires} 字段，如果当前时间 >= 过期时间则认为已过期。
     *
     * @return 如果已过期返回 {@code true}，否则返回 {@code false}
     */
    public boolean isExpired() {
        return System.currentTimeMillis() >= expires;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OAuthCredentials that)) return false;
        return expires == that.expires
                && Objects.equals(refresh, that.refresh)
                && Objects.equals(access, that.access)
                && Objects.equals(extra, that.extra);
    }

    @Override
    public int hashCode() {
        return Objects.hash(refresh, access, expires, extra);
    }
}