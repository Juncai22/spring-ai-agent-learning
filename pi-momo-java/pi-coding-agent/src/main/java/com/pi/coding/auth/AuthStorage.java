package com.pi.coding.auth;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证存储管理器，负责管理 AI 提供商凭证的存取和解析。
 *
 * <p>AuthStorage 是整个认证模块的核心门面类，提供以下核心功能：
 * <ul>
 *   <li>凭证管理 - 存储、获取、删除各提供商的认证凭证</li>
 *   <li>运行时覆盖 - 支持在运行时临时设置 API 密钥覆盖，优先级最高</li>
 *   <li>多级 API 密钥解析 - 按预定义优先级顺序自动查找可用密钥</li>
 *   <li>OAuth 流程 - 支持完整的 OAuth 登录、注销和令牌自动刷新</li>
 *   <li>持久化 - 自动将凭证变更保存到后端存储</li>
 * </ul>
 *
 * <p>API 密钥解析优先级顺序（从高到低）：
 * <ol>
 *   <li>Runtime override（运行时覆盖，通过 {@link #setRuntimeApiKey} 设置）</li>
 *   <li>Stored API key credential（持久化存储的 API 密钥凭证）</li>
 *   <li>OAuth access token（有效的 OAuth 访问令牌，未过期时）</li>
 *   <li>Environment variable（环境变量，格式为 {@code PROVIDER_API_KEY}）</li>
 *   <li>Fallback resolver（兜底解析器，回调方式提供）</li>
 * </ol>
 *
 * <p>创建方式：
 * <ul>
 *   <li>{@link #create(String)} - 文件持久化方式，适合生产环境</li>
 *   <li>{@link #inMemory()} - 内存存储方式，适合测试环境</li>
 *   <li>{@link #withBackend(AuthStorageBackend)} - 自定义后端方式</li>
 * </ul>
 *
 * @see AuthCredential
 * @see AuthStorageBackend
 * @see OAuthProvider
 * @see FallbackResolver
 */
public class AuthStorage {
    
    /** 令牌即将过期时触发刷新的默认缓冲时间，设为 5 分钟（300,000 毫秒）。 */
    private static final long REFRESH_BUFFER_MS = 5 * 60 * 1000;
    
    /** 后端存储实例，负责实际的凭证持久化读写操作。 */
    private final AuthStorageBackend backend;
    /** 凭证缓存，以提供商 ID 为键的并发安全映射，存储各提供商当前有效的认证凭证。 */
    private final Map<String, AuthCredential> credentials;
    /** 运行时 API 密钥覆盖映射，优先级最高，仅在当前 JVM 生命周期内有效。 */
    private final Map<String, String> runtimeApiKeys = new ConcurrentHashMap<>();
    /** 已注册的 OAuth 提供商映射，用于处理 OAuth 登录和令牌刷新流程。 */
    private final Map<String, OAuthProvider> oauthProviders = new ConcurrentHashMap<>();
    
    /**
     * 私有构造方法，使用指定的后端存储创建 AuthStorage 实例。
     *
     * <p>初始化时从后端存储加载已有的凭证数据到内存缓存中。
     *
     * @param backend 后端存储实现，不能为 null
     */
        this.backend = Objects.requireNonNull(backend, "backend must not be null");
        this.credentials = new ConcurrentHashMap<>(backend.load());
    }
    
    /**
     * 使用文件持久化方式创建 AuthStorage 实例。
     *
     * <p>凭证数据将以 JSON 格式持久化到指定文件中，可在 JVM 重启后恢复。
     * 适用于生产环境，文件路径建议设置在用户主目录下的安全位置。
     *
     * @param filePath 凭证文件的路径，如 "~/.pi-coding/credentials.json"
     * @return 新的 AuthStorage 实例
     */
    public static AuthStorage create(String filePath) {
        return new AuthStorage(new FileAuthStorageBackend(filePath));
    }
    
    /**
     * 使用内存存储方式创建 AuthStorage 实例（适用于测试环境）。
     *
     * <p>凭证仅存储在内存中，JVM 退出后数据丢失。
     * 主要用于单元测试场景，避免在测试中产生文件残留。
     *
     * @return 新的 AuthStorage 实例
     */
    public static AuthStorage inMemory() {
        return new AuthStorage(new InMemoryAuthStorageBackend());
    }
    
    /**
     * 使用自定义后端存储创建 AuthStorage 实例。
     *
     * <p>允许调用方传入自定义的 {@link AuthStorageBackend} 实现，
     * 可用于数据库存储、加密存储等特殊场景。
     *
     * @param backend 自定义的后端存储实现
     * @return 新的 AuthStorage 实例
     */
    public static AuthStorage withBackend(AuthStorageBackend backend) {
        return new AuthStorage(backend);
    }
    
    // ========== 凭证管理（Credential Management） ==========
    
    /**
     * 为指定提供商设置认证凭证。
     *
     * <p>设置后会自动触发持久化保存，确保凭证数据不会丢失。
     * 如果该提供商已存在凭证，将被新凭证覆盖。
     *
     * @param provider   提供商 ID，如 "anthropic"、"openai"
     * @param credential 要存储的认证凭证对象
     */
    public void setCredential(String provider, AuthCredential credential) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(credential, "credential must not be null");
        credentials.put(provider, credential);
        persist();
    }
    
    /**
     * 获取指定提供商的认证凭证。
     *
     * <p>从内存缓存中直接返回凭证对象，不会触发后端存储的读取操作。
     * 如果该提供商尚未设置凭证，返回 null。
     *
     * @param provider 提供商 ID
     * @return 该提供商的认证凭证，如果不存在则返回 null
     */
    public AuthCredential getCredential(String provider) {
        return credentials.get(provider);
    }
    
    /**
     * 移除指定提供商的认证凭证。
     *
     * <p>移除后会自动触发持久化保存，确保存储与内存状态一致。
     * 如果该提供商没有凭证，此操作不会产生任何影响。
     *
     * @param provider 提供商 ID
     */
    public void removeCredential(String provider) {
        credentials.remove(provider);
        persist();
    }
    
    /**
     * 检查指定提供商是否已存在认证凭证。
     *
     * @param provider 提供商 ID
     * @return 如果该提供商已设置凭证返回 true，否则返回 false
     */
    public boolean hasCredential(String provider) {
        return credentials.containsKey(provider);
    }
    
    // ========== 运行时覆盖（Runtime Override） ==========
    
    /**
     * 为指定提供商设置运行时 API 密钥覆盖。
     *
     * <p>运行时覆盖的优先级最高，在所有凭证解析中优先被返回。
     * 此密钥仅存在于当前 JVM 内存中，不会持久化到文件。
     * 适用于临时切换密钥、从安全输入动态获取密钥等场景。
     *
     * @param provider 提供商 ID
     * @param apiKey   要覆盖的 API 密钥
     */
    public void setRuntimeApiKey(String provider, String apiKey) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(apiKey, "apiKey must not be null");
        runtimeApiKeys.put(provider, apiKey);
    }
    
    /**
     * 清除指定提供商的运行时 API 密钥覆盖。
     *
     * <p>清除后，密钥解析将回退到下一优先级（持久化凭证/环境变量等）。
     *
     * @param provider 提供商 ID
     */
    public void clearRuntimeApiKey(String provider) {
        runtimeApiKeys.remove(provider);
    }
    
    // ========== API 密钥解析（API Key Resolution） ==========
    
    /**
     * 获取指定提供商的 API 密钥（不使用兜底解析器）。
     *
     * <p>按照标准解析优先级顺序查找可用的 API 密钥。
     *
     * @param provider 提供商 ID
     * @return 解析得到的 API 密钥，如果所有来源均未找到则返回 null
     */
    public String getApiKey(String provider) {
        return getApiKey(provider, null);
    }
    
    /**
     * 获取指定提供商的 API 密钥，支持兜底解析器。
     *
     * <p>按照以下优先级依次查找 API 密钥：
     * <ol>
     *   <li>运行时覆盖（Runtime override）</li>
     *   <li>持久化存储的 API 密钥凭证</li>
     *   <li>有效的 OAuth 访问令牌（未过期时）</li>
     *   <li>环境变量（格式为 {@code PROVIDER_API_KEY}，如 ANTHROPIC_API_KEY）</li>
     *   <li>兜底解析器（Fallback resolver，通过回调方式提供）</li>
     * </ol>
     *
     * @param provider 提供商 ID
     * @param fallback 可选的兜底解析器，当所有标准来源均未找到密钥时调用
     * @return 解析得到的 API 密钥，如果所有来源均未找到则返回 null
     */
    public String getApiKey(String provider, FallbackResolver fallback) {
        // 1. 运行时覆盖（Runtime override）：优先级最高，仅在当前 JVM 生命周期内有效
        String runtimeKey = runtimeApiKeys.get(provider);
        if (runtimeKey != null) {
            return runtimeKey;
        }
        
        // 2. 持久化存储的凭证（Stored credential）：从文件或内存中加载的已有凭证
        AuthCredential credential = credentials.get(provider);
        if (credential != null) {
            if (credential instanceof ApiKeyCredential apiKey) {
                return apiKey.apiKey();
            }
            if (credential instanceof OAuthCredential oauth && !oauth.isExpired()) {
                return oauth.accessToken();
            }
        }
        
        // 3. 环境变量（Environment variable）：从系统环境变量中读取，格式为 PROVIDER_API_KEY
        String envKey = getEnvApiKey(provider);
        if (envKey != null) {
            return envKey;
        }
        
        // 4. 兜底解析器（Fallback resolver）：所有标准来源均未找到时，通过回调方式尝试获取
        if (fallback != null) {
            return fallback.resolve(provider);
        }
        
        return null;
    }
    
    /**
     * 从环境变量中获取 API 密钥。
     *
     * <p>环境变量名称的生成规则：将提供商 ID 转换为大写，并将连字符替换为下划线，
     * 然后在末尾追加 "_API_KEY"。例如：
     * <ul>
     *   <li>提供商 "anthropic" 对应环境变量 "ANTHROPIC_API_KEY"</li>
     *   <li>提供商 "open-ai" 对应环境变量 "OPEN_AI_API_KEY"</li>
     * </ul>
     *
     * @param provider 提供商 ID
     * @return 从环境变量中读取的 API 密钥，如果环境变量不存在则返回 null
     */
    private String getEnvApiKey(String provider) {
        // Try PROVIDER_API_KEY format (e.g., ANTHROPIC_API_KEY)
        String envName = provider.toUpperCase().replace("-", "_") + "_API_KEY";
        return System.getenv(envName);
    }
    
    // ========== OAuth 流程（OAuth Flow） ==========
    
    /**
     * 注册一个 OAuth 提供商实现，用于处理该提供商的 OAuth 认证流程。
     *
     * <p>注册后，可以通过 {@link #login(String, OAuthLoginCallbacks)} 发起 OAuth 登录流程，
     * 或通过 {@link #refreshIfNeeded(String)} 自动刷新访问令牌。
     * 每个提供商 ID 只能注册一个 OAuthProvider 实现。
     *
     * @param provider OAuth 提供商实现，不能为 null
     */
    public void registerOAuthProvider(OAuthProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        oauthProviders.put(provider.providerId(), provider);
    }
    
    /**
     * 发起 OAuth 登录流程。
     *
     * <p>完整的 OAuth 登录流程包括：
     * <ol>
     *   <li>生成随机 state 参数用于 CSRF 防护</li>
     *   <li>获取授权 URL 并通知调用方打开浏览器</li>
     *   <li>等待用户授权并接收回调的授权码</li>
     *   <li>将授权码交换为访问令牌和刷新令牌</li>
     *   <li>保存凭证到持久化存储</li>
     *   <li>调用成功或失败的回调</li>
     * </ol>
     *
     * <p>此方法返回 {@link CompletableFuture}，调用方可以同步等待（.join()）或异步处理。
     *
     * @param provider  提供商 ID
     * @param callbacks OAuth 登录回调接口，用于打开浏览器、接收授权码等
     * @return 一个 CompletableFuture，登录完成后返回，失败时包含异常信息
     */
    public CompletableFuture<Void> login(String provider, OAuthLoginCallbacks callbacks) {
        OAuthProvider oauthProvider = oauthProviders.get(provider);
        if (oauthProvider == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("No OAuth provider registered for: " + provider));
        }
        
        String state = UUID.randomUUID().toString();
        String authUrl = oauthProvider.getAuthorizationUrl(state);
        
        return callbacks.openAuthUrl(authUrl)
            .thenCompose(v -> callbacks.receiveAuthCode())
            .thenCompose(oauthProvider::exchangeCode)
            .thenAccept(credential -> {
                setCredential(provider, credential);
                callbacks.onSuccess(credential);
            })
            .exceptionally(error -> {
                callbacks.onError(error);
                throw new RuntimeException(error);
            });
    }
    
    /**
     * 从指定提供商登出，移除其 OAuth 凭证。
     *
     * <p>仅移除 OAuth 类型的凭证，API 密钥凭证不受影响。
     * 移除后会自动触发持久化保存。
     *
     * @param provider 提供商 ID
     */
    public void logout(String provider) {
        AuthCredential credential = credentials.get(provider);
        if (credential instanceof OAuthCredential) {
            removeCredential(provider);
        }
    }
    
    /**
     * 检查并刷新 OAuth 令牌（如果已过期或即将过期）。
     *
     * <p>此方法会自动判断是否需要刷新令牌：
     * <ul>
     *   <li>如果令牌未过期且不在缓冲窗口内，直接返回当前访问令牌</li>
     *   <li>如果令牌即将过期（在 {@link #REFRESH_BUFFER_MS} 缓冲时间内），
     *       使用 OAuth 提供商的刷新方法自动刷新令牌</li>
     *   <li>刷新成功后，会自动将新凭证保存到持久化存储</li>
     * </ul>
     *
     * <p>建议在每次调用 AI 提供商 API 前调用此方法，确保使用有效的访问令牌。
     *
     * @param provider 提供商 ID
     * @return 一个 CompletableFuture，返回有效的访问令牌字符串
     * @throws IllegalStateException 如果该提供商没有 OAuth 凭证
     * @throws IllegalArgumentException 如果该提供商没有注册 OAuthProvider
     */
    public CompletableFuture<String> refreshIfNeeded(String provider) {
        AuthCredential credential = credentials.get(provider);
        
        if (!(credential instanceof OAuthCredential oauth)) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("No OAuth credential for provider: " + provider));
        }
        
        // 检查是否需要刷新：如果令牌未在缓冲窗口内，直接返回当前令牌
        if (!oauth.isExpiringSoon(REFRESH_BUFFER_MS)) {
            return CompletableFuture.completedFuture(oauth.accessToken());
        }
        
        OAuthProvider oauthProvider = oauthProviders.get(provider);
        if (oauthProvider == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("No OAuth provider registered for: " + provider));
        }
        
        return oauthProvider.refreshToken(oauth)
            .thenApply(refreshed -> {
                setCredential(provider, refreshed);
                return refreshed.accessToken();
            });
    }
    
    /**
     * 检查指定提供商是否正在使用 OAuth 认证方式。
     *
     * @param provider 提供商 ID
     * @return 如果该提供商使用的是 OAuth 凭证返回 true，否则返回 false
     */
    public boolean isUsingOAuth(String provider) {
        return credentials.get(provider) instanceof OAuthCredential;
    }
    
    // ========== 持久化（Persistence） ==========

    /**
     * 将当前内存中的凭证数据持久化保存到后端存储。
     *
     * <p>创建一个副本传入后端存储，避免外部修改影响持久化数据的一致性。
     */
        backend.save(new HashMap<>(credentials));
    }
    
    /**
     * 从后端存储重新加载凭证数据到内存缓存。
     *
     * <p>当其他进程可能修改了持久化文件时，可以调用此方法同步最新状态。
     * 会清空当前内存中的所有凭证，然后从后端存储重新加载。
     */
    public void reload() {
        credentials.clear();
        credentials.putAll(backend.load());
    }
}
