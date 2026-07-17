package com.pi.coding.auth;

/**
 * 兜底 API 密钥解析器函数式接口。
 *
 * <p>当 {@link AuthStorage} 按照标准优先级顺序（运行时覆盖、持久化凭证、OAuth 令牌、环境变量）
 * 均未找到有效的 API 密钥时，会调用此接口的实现作为最后的兜底方案。
 *
 * <p>典型的兜底解析策略包括：
 * <ul>
 *   <li>从配置管理服务（如 AWS Secrets Manager、Vault）获取密钥</li>
 *   <li>从加密的配置文件读取密钥</li>
 *   <li>提示用户手动输入密钥</li>
 *   <li>返回默认的开发测试密钥</li>
 * </ul>
 *
 * <p>此接口标注为 {@link FunctionalInterface}，可以使用 Lambda 表达式简洁地实现，例如：
 * <pre>{@code
 * AuthStorage storage = AuthStorage.create("credentials.json");
 * String key = storage.getApiKey("anthropic", provider -> System.getenv("FALLBACK_API_KEY"));
 * }</pre>
 *
 * @see AuthStorage#getApiKey(String, FallbackResolver)
 */
@FunctionalInterface
public interface FallbackResolver {
    
    /**
     * 为指定提供商解析兜底 API 密钥。
     *
     * <p>当所有标准来源均未找到密钥时，此方法被调用以尝试从其他来源获取密钥。
     * 如果无法获取密钥，应返回 null 而非抛出异常，以便上层调用方进行后续处理。
     *
     * @param provider 提供商 ID，如 "anthropic"、"openai"
     * @return 兜底解析得到的 API 密钥，如果无法获取则返回 null
     */
    String resolve(String provider);
}
