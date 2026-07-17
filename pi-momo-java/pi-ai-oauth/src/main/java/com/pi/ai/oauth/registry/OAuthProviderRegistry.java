package com.pi.ai.oauth.registry;

import com.pi.ai.oauth.spi.OAuthCredentials;
import com.pi.ai.oauth.spi.OAuthProviderInterface;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth Provider 注册表，统一管理所有 OAuth 认证服务提供商的注册、查找、注销和令牌刷新。
 *
 * <p>该注册表采用单例模式（通过静态方法访问），使用 {@link ConcurrentHashMap} 保证线程安全。
 * 支持两种类型的 Provider：
 * <ul>
 *   <li><b>内置 Provider</b>：系统自带的 OAuth Provider，注册后不可被永久移除，注销后自动恢复为默认实现</li>
 *   <li><b>自定义 Provider</b>：用户或插件注册的 Provider，可被完全移除</li>
 * </ul>
 *
 * <p>核心功能：
 * <ul>
 *   <li>注册/注销 Provider（区分内置和自定义）</li>
 *   <li>按 ID 查找或列出所有 Provider</li>
 *   <li>重置为仅含内置 Provider 的初始状态</li>
 *   <li>获取 OAuth API Key，并自动刷新过期的令牌</li>
 * </ul>
 *
 * <p>对应 pi-mono 前端的 oauthProviderRegistry。
 */
public final class OAuthProviderRegistry {

    /** 存储所有已注册 Provider 的线程安全映射表，key 为 Provider ID，value 为 Provider 实例 */
    private static final Map<String, OAuthProviderInterface> registry = new ConcurrentHashMap<>();

    /** 存储所有内置 Provider 的列表，用于在注销时恢复默认实现 */
    private static final List<OAuthProviderInterface> builtInProviders = new ArrayList<>();

    /** 私有构造器，防止实例化 */
    private OAuthProviderRegistry() {}

    /**
     * 注册一个内置 OAuth Provider。
     * <p>内置 Provider 会被同时添加到 builtInProviders 列表和 registry 映射表中。
     * 当调用 {@link #unregister(String)} 注销内置 Provider 时，会自动从 builtInProviders 列表恢复。
     * <p>此方法通常在系统初始化时调用，由 {@link com.pi.ai.oauth.builtin.BuiltInOAuthProviders} 统一管理。
     *
     * @param provider 要注册的内置 OAuth Provider，不可为 null
     */
    public static void registerBuiltIn(OAuthProviderInterface provider) {
        builtInProviders.add(provider);
        registry.put(provider.id(), provider);
    }

    /**
     * 注册一个自定义 OAuth Provider。
     * <p>自定义 Provider 仅加入到 registry 映射表中，不会加入 builtInProviders 列表。
     * 因此当调用 {@link #unregister(String)} 时，自定义 Provider 会被完全移除。
     * <p>如果已存在同 ID 的 Provider（包括内置 Provider），新的 Provider 会覆盖旧的。
     *
     * @param provider 要注册的自定义 OAuth Provider，不可为 null
     */
    public static void register(OAuthProviderInterface provider) {
        registry.put(provider.id(), provider);
    }

    /**
     * 根据唯一标识符获取已注册的 OAuth Provider。
     *
     * @param id Provider 的唯一标识符，例如 "anthropic"、"github-copilot" 等，不可为 null
     * @return 匹配的 OAuth Provider 实例，如果未找到则返回 null
     */
    public static OAuthProviderInterface get(String id) {
        return registry.get(id);
    }

    /**
     * 获取所有已注册的 OAuth Provider 的不可修改列表。
     *
     * @return 包含所有已注册 Provider 的列表，不会为 null
     */
    public static List<OAuthProviderInterface> getAll() {
        return List.copyOf(registry.values());
    }

    /**
     * 注销指定 ID 的 OAuth Provider。
     *
     * <p>注销行为根据 Provider 类型有所不同：
     * <ul>
     *   <li>如果是内置 Provider，不会将其从 registry 中移除，而是恢复为 builtInProviders 列表中的默认实现</li>
     *   <li>如果是自定义 Provider，直接从 registry 中移除</li>
     *   <li>如果指定 ID 不存在，则不做任何操作</li>
     * </ul>
     *
     * @param id 要注销的 Provider 唯一标识符，不可为 null
     */
    public static void unregister(String id) {
        OAuthProviderInterface builtIn = builtInProviders.stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElse(null);
        if (builtIn != null) {
            // 内置 Provider：恢复为默认实现
            registry.put(id, builtIn);
        } else {
            // 自定义 Provider：完全移除
            registry.remove(id);
        }
    }

    /**
     * 重置注册表，清空所有 Provider 后重新注册所有内置 Provider。
     * <p>此操作会移除所有自定义 Provider，使注册表恢复到仅包含内置 Provider 的初始状态。
     */
    public static void reset() {
        registry.clear();
        for (OAuthProviderInterface provider : builtInProviders) {
            registry.put(provider.id(), provider);
        }
    }

    /**
     * 获取指定 Provider 的 OAuth API Key，并在凭证过期时自动刷新。
     *
     * <p>该方法执行以下逻辑：
     * <ol>
     *   <li>根据 providerId 查找对应的 Provider 实例</li>
     *   <li>从凭证映射中获取该 Provider 的凭证</li>
     *   <li>如果凭证未过期，直接转换为 API Key 返回</li>
     *   <li>如果凭证已过期，调用 {@link OAuthProviderInterface#refreshToken(OAuthCredentials)} 刷新后返回</li>
     * </ol>
     *
     * @param providerId  Provider 的唯一标识符，不可为 null
     * @param credentials 所有 Provider 的凭证映射表，key 为 Provider ID，value 为对应凭证，不可为 null
     * @return 包含 API Key 和更新后凭证的 CompletableFuture；如果该 Provider 无凭证则返回 null
     * @throws IllegalArgumentException 如果 providerId 对应的 Provider 不存在
     * @throws RuntimeException         如果刷新令牌时发生异常
     */
    public static CompletableFuture<OAuthApiKeyResult> getOAuthApiKey(
            String providerId, Map<String, OAuthCredentials> credentials) {
        OAuthProviderInterface provider = get(providerId);
        if (provider == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown OAuth provider: " + providerId));
        }

        OAuthCredentials creds = credentials.get(providerId);
        if (creds == null) {
            return CompletableFuture.completedFuture(null);
        }

        // 凭证未过期，直接返回 API Key
        if (!creds.isExpired()) {
            String apiKey = provider.getApiKey(creds);
            return CompletableFuture.completedFuture(new OAuthApiKeyResult(creds, apiKey));
        }

        // 凭证已过期，自动刷新令牌
        return provider.refreshToken(creds)
                .thenApply(newCreds -> {
                    String apiKey = provider.getApiKey(newCreds);
                    return new OAuthApiKeyResult(newCreds, apiKey);
                })
                .exceptionally(e -> {
                    throw new RuntimeException(
                            "Failed to refresh OAuth token for " + providerId, e);
                });
    }

    /**
     * OAuth API Key 结果记录，封装刷新后的凭证和对应的 API Key。
     *
     * @param newCredentials 刷新后的 OAuth 凭证（如果未过期则为原始凭证）
     * @param apiKey         从凭证转换得到的 API Key 字符串
     */
    public record OAuthApiKeyResult(OAuthCredentials newCredentials, String apiKey) {}
}