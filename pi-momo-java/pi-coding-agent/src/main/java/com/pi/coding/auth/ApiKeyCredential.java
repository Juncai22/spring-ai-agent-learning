package com.pi.coding.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * API 密钥凭证记录，用于简单的 API 密钥认证方式。
 *
 * <p>这是最直接的认证方式，客户端使用一个静态的 API 密钥字符串来标识身份。
 * 适用于大多数 AI 服务提供商（如 Anthropic、OpenAI 等）的 API 密钥认证场景。
 *
 * <p>ApiKeyCredential 是一个 Java Record，自动生成了构造方法、equals、hashCode 和 toString 方法。
 * 使用 {@link JsonCreator} 注解支持 Jackson JSON 反序列化，方便从持久化存储中恢复凭证。
 * 构造时会通过 {@link Objects#requireNonNull} 确保 apiKey 不为空。
 *
 * <p>注意：API 密钥以明文形式存储在内存中，建议在不需要时及时清除引用，以减少安全风险。
 *
 * @param apiKey API 密钥值，即提供商颁发的认证令牌字符串
 */
public record ApiKeyCredential(String apiKey) implements AuthCredential {
    
    @JsonCreator
    public ApiKeyCredential(@JsonProperty("apiKey") String apiKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
    }
    
    @Override
    public String type() {
        return "apiKey";
    }
}
