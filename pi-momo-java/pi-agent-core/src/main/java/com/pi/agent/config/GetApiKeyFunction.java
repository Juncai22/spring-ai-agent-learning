package com.pi.agent.config;

import java.util.concurrent.CompletableFuture;

/**
 * 可选的回调接口，用于在每次 LLM 调用时动态解析 API Key。
 *
 * <p>当配置了此回调后，Agent 主循环会在每次流式调用之前调用该函数，
 * 传入 provider 标识符（如 {@code "anthropic"}、{@code "openai"}）。
 * 解析出的 API Key 会覆盖 {@link SimpleStreamOptions} 中静态配置的 API Key。
 *
 * <p>此功能在以下场景中非常有用：
 * <ul>
 *   <li>多租户场景：不同用户使用不同的 API Key</li>
 *   <li>动态密钥轮换：支持密钥的定期或不定期更换</li>
 *   <li>密钥管理服务：从密钥管理服务（KMS）中安全获取密钥</li>
 *   <li>多模型路由：根据不同的模型提供商使用不同的 API Key</li>
 * </ul>
 *
 * <p><b>验证的需求：13.7</b>
 */
@FunctionalInterface
public interface GetApiKeyFunction {

    /**
     * 为指定 provider 解析 API Key。
     * <p>此方法在每次 LLM 流式调用前被调用，返回的 API Key 将用于本次调用。
     * 实现可以使用异步操作（如远程调用、密钥管理服务查询）来获取密钥。
     *
     * @param provider LLM 提供商标识符，如 "anthropic"、"openai"、"azure" 等
     * @return 一个 CompletableFuture，异步解析为 API Key 字符串
     */
    CompletableFuture<String> getApiKey(String provider);
}
