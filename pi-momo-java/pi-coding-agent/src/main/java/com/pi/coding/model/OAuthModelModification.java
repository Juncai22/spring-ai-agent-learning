package com.pi.coding.model;

import java.util.Map;

/**
 * OAuth 模型修改配置 —— 当提供商使用 OAuth 认证时，对该提供商下的模型进行的修改。
 *
 * <p>OAuth 认证模式下，模型的 API 端点地址和请求头可能与标准 API Key 模式不同。
 * 该记录允许覆盖模型的 baseUrl（基础地址）和 headers（请求头），
 * 使得模型能够通过 OAuth 流程获得的令牌正确访问提供商 API。</p>
 *
 * <p>使用场景：当 {@link CodingModelRegistry#applyOAuthModification} 被调用时，
 * 该修改会被应用到指定提供商的所有模型上。</p>
 *
 * @param baseUrl OAuth 模式下的 API 基础地址，覆盖模型的原始 baseUrl
 * @param headers OAuth 模式下的额外/覆盖请求头（如 Authorization: Bearer xxx）
 */
public record OAuthModelModification(
    String baseUrl,
    Map<String, String> headers
) {}
