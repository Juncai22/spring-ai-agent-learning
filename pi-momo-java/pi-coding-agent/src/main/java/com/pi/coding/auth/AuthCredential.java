package com.pi.coding.auth;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 认证凭证密封接口。
 *
 * <p>定义 AI 提供商认证凭证的统一抽象，支持两种凭证类型：
 * <ul>
 *   <li>{@link ApiKeyCredential} - 基于 API 密钥的简单认证方式，适用于大多数 AI 服务提供商</li>
 *   <li>{@link OAuthCredential} - 基于 OAuth 2.0 令牌的认证方式，支持访问令牌自动刷新</li>
 * </ul>
 *
 * <p>通过 Jackson 的 {@link JsonTypeInfo} 和 {@link JsonSubTypes} 注解实现多态序列化/反序列化，
 * 确保凭证对象可以方便地存储为 JSON 格式并在不同会话间持久化。type 属性用于区分凭证类型。
 *
 * @see ApiKeyCredential
 * @see OAuthCredential
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ApiKeyCredential.class, name = "apiKey"),
    @JsonSubTypes.Type(value = OAuthCredential.class, name = "oauth")
})
public sealed interface AuthCredential permits ApiKeyCredential, OAuthCredential {
    
    /**
     * 返回当前凭证的类型标识符。
     *
     * <p>该值对应 JSON 序列化时的 "type" 字段，用于反序列化时正确地将 JSON 数据映射到对应的凭证子类型。
     *
     * @return 凭证类型标识符，返回 "apiKey" 表示 API 密钥凭证，返回 "oauth" 表示 OAuth 凭证
     */
    String type();
}
