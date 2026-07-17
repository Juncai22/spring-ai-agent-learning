package com.pi.coding.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pi.ai.core.registry.ModelRegistry;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.ModelCost;
import com.pi.ai.core.util.PiAiJson;
import com.pi.coding.auth.AuthStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编码 Agent 模型注册中心 —— 扩展内置模型注册中心，支持多来源模型管理和动态提供商注册。
 *
 * <p>该类是 Agent 模型管理的核心组件，负责聚合和管理来自不同来源的 AI 模型信息，
 * 提供统一的模型查询、API Key 解析和动态注册能力。</p>
 *
 * <h3>支持的模型来源（按优先级从高到低）</h3>
 * <ol>
 *   <li><b>动态提供商（Dynamic Providers）</b>：扩展通过 {@link #registerProvider} 注册的提供商，
 *       包含完整的 baseUrl、headers 和模型列表，完全独立于核心配置</li>
 *   <li><b>自定义模型（Custom Models）</b>：用户通过 models.json 文件提供的自定义模型配置，
 *       可用于覆盖内置模型或添加新模型</li>
 *   <li><b>内置模型（Built-in Models）</b>：pi-ai-core 的 models.json 中预定义的模型，
 *       作为兜底模型来源</li>
 * </ol>
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li><b>模型查询</b>：按提供商和模型 ID 查找模型，支持三级搜索链</li>
 *   <li><b>API Key 解析</b>：支持动态提供商内联 Key、OAuth 刷新和标准 Key 存储三种方式</li>
 *   <li><b>动态提供商注册</b>：允许扩展在运行时注册/注销完整的模型提供商</li>
 *   <li><b>OAuth 修改</b>：对配置了 OAuth 认证的提供商，自动覆盖模型端点地址和请求头</li>
 * </ul>
 */
public class CodingModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(CodingModelRegistry.class);

    /** 认证存储，用于解析各提供商的 API Key 和 OAuth 令牌 */
    private final AuthStorage authStorage;

    /**
     * 内置模型缓存：provider -> (modelId -> Model)
     * 从 pi-ai-core 的 ModelRegistry 加载，不可变（unmodifiable）。
     */
    private final Map<String, Map<String, Model>> builtinModels;

    /**
     * 自定义模型缓存：provider -> (modelId -> Model)
     * 从用户提供的 models.json 文件加载，使用 ConcurrentHashMap 保证线程安全。
     * 自定义模型优先级高于内置模型。
     */
    private final Map<String, Map<String, Model>> customModels = new ConcurrentHashMap<>();

    /**
     * 动态注册的提供商：providerId -> ProviderConfig
     * 由扩展在运行时注册，优先级最高。
     * 使用 ConcurrentHashMap 保证线程安全。
     */
    private final Map<String, ProviderConfig> dynamicProviders = new ConcurrentHashMap<>();

    /**
     * OAuth 模型修改配置：provider -> OAuthModelModification
     * 当提供商配置了 OAuth 认证时，自动应用修改以覆盖模型的 API 端点和请求头。
     */
    private final Map<String, OAuthModelModification> oauthModifications = new ConcurrentHashMap<>();

    /**
     * 创建模型注册中心实例。
     *
     * @param authStorage    认证存储实例，用于 API Key 和 OAuth 令牌管理，不能为 null
     * @param modelsJsonPath 可选的用户自定义 models.json 文件路径，可为 null
     */
    public CodingModelRegistry(AuthStorage authStorage, String modelsJsonPath) {
        this.authStorage = Objects.requireNonNull(authStorage, "authStorage must not be null");
        this.builtinModels = loadBuiltinModels();

        // 如果提供了自定义模型文件路径，尝试加载
        if (modelsJsonPath != null) {
            loadCustomModels(modelsJsonPath);
        }
    }

    /**
     * 创建仅包含内置模型的模型注册中心实例（无自定义模型）。
     *
     * @param authStorage 认证存储实例，用于 API Key 和 OAuth 令牌管理，不能为 null
     */
    public CodingModelRegistry(AuthStorage authStorage) {
        this(authStorage, null);
    }

    // ========== 模型查询（Model Queries） ==========

    /**
     * 按提供商和模型 ID 查找模型。
     *
     * <p>搜索顺序（优先级从高到低）：</p>
     * <ol>
     *   <li><b>动态提供商</b>：检查是否已注册的动态提供商中有该模型</li>
     *   <li><b>自定义模型</b>：检查用户自定义 models.json 中是否有该模型</li>
     *   <li><b>内置模型</b>：回退到 pi-ai-core 的内置模型注册表</li>
     * </ol>
     *
     * <p>自定义模型和内置模型找到后还会应用 OAuth 修改（如果存在）。</p>
     *
     * @param provider 提供商 ID，如 "openai"、"anthropic"
     * @param modelId  模型 ID，如 "gpt-4"、"claude-3-opus"
     * @return 找到的 Model 对象，如果未找到则返回 null
     */
    public Model find(String provider, String modelId) {
        // 1. 优先检查动态注册的提供商（优先级最高）
        ProviderConfig dynProvider = dynamicProviders.get(provider);
        if (dynProvider != null) {
            Model dynModel = buildModelFromProvider(dynProvider, modelId);
            if (dynModel != null) return dynModel;
        }

        // 2. 检查自定义模型（优先级高于内置模型，低于动态提供商）
        Map<String, Model> customProviderModels = customModels.get(provider);
        if (customProviderModels != null) {
            Model custom = customProviderModels.get(modelId);
            if (custom != null) return applyOAuthModification(custom);
        }

        // 3. 回退到内置模型注册表
        Model builtin = ModelRegistry.getModel(provider, modelId);
        if (builtin != null) return applyOAuthModification(builtin);

        return null;
    }

    /**
     * 获取所有可用的模型列表（即有有效 API Key 配置的模型）。
     *
     * <p>遍历所有已知的提供商（内置 + 自定义 + 动态），
     * 仅返回那些在 {@link AuthStorage} 中配置了 API Key 的提供商的模型。
     * 结果是不可修改的视图。</p>
     *
     * @return 可用模型列表（不可修改），不会返回 null
     */
    public List<Model> getAvailableModels() {
        List<Model> result = new ArrayList<>();

        // 收集所有提供商的 ID（使用 LinkedHashSet 保持顺序并去重）
        Set<String> allProviders = new LinkedHashSet<>();
        allProviders.addAll(ModelRegistry.getProviders());
        allProviders.addAll(customModels.keySet());
        allProviders.addAll(dynamicProviders.keySet());

        for (String provider : allProviders) {
            // 仅当该提供商有可用的 API Key 时才返回其模型
            if (authStorage.getApiKey(provider) != null) {
                result.addAll(getModelsForProvider(provider));
            }
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * 获取所有已注册的提供商 ID 列表。
     *
     * <p>合并内置、自定义和动态提供商，使用 LinkedHashSet 去重并保持顺序。</p>
     *
     * @return 提供商 ID 的不可变列表
     */
    public List<String> getProviders() {
        Set<String> providers = new LinkedHashSet<>();
        providers.addAll(ModelRegistry.getProviders());
        providers.addAll(customModels.keySet());
        providers.addAll(dynamicProviders.keySet());
        return List.copyOf(providers);
    }

    /**
     * 获取指定提供商下的所有模型（合并自定义模型和内置模型）。
     *
     * <p>合并规则：</p>
     * <ol>
     *   <li>以内置模型列表为起点</li>
     *   <li>用自定义模型覆盖同 ID 的内置模型（自定义优先级更高）</li>
     *   <li>添加动态提供商中的模型</li>
     * </ol>
     *
     * <p>所有模型在返回前会应用 OAuth 修改（如果存在）。</p>
     *
     * @param provider 提供商 ID
     * @return 该提供商下的模型列表（不可修改）
     */
    public List<Model> getModelsForProvider(String provider) {
        // 使用 LinkedHashMap 保持插入顺序，后插入的自定义模型会覆盖同 ID 的内置模型
        Map<String, Model> merged = new LinkedHashMap<>();

        // 1. 从内置模型开始
        for (Model m : ModelRegistry.getModels(provider)) {
            merged.put(m.id(), applyOAuthModification(m));
        }

        // 2. 用自定义模型覆盖（自定义模型优先级更高）
        Map<String, Model> custom = customModels.get(provider);
        if (custom != null) {
            for (Model m : custom.values()) {
                merged.put(m.id(), applyOAuthModification(m));
            }
        }

        // 3. 添加动态提供商中的模型
        ProviderConfig dynProvider = dynamicProviders.get(provider);
        if (dynProvider != null && dynProvider.models() != null) {
            for (ProviderModelConfig mc : dynProvider.models()) {
                Model m = buildModelFromConfig(dynProvider, mc);
                merged.put(m.id(), m);
            }
        }

        return List.copyOf(merged.values());
    }

    // ========== API Key 解析（API Key Resolution） ==========

    /**
     * 获取指定模型的 API Key。
     *
     * <p>委托给 {@link #getApiKeyForProvider}，按提供商查询 API Key。</p>
     *
     * @param model 要查询 API Key 的模型
     * @return 包含 API Key 的 CompletableFuture
     * @throws IllegalStateException 如果无法找到 API Key
     */
    public CompletableFuture<String> getApiKey(Model model) {
        return getApiKeyForProvider(model.provider());
    }

    /**
     * 获取指定提供商的 API Key。
     *
     * <p>API Key 解析顺序：</p>
     * <ol>
     *   <li>检查动态提供商是否配置了内联 API Key，如果有则直接返回</li>
     *   <li>检查提供商是否使用 OAuth 认证，如果是则尝试刷新令牌</li>
     *   <li>从 {@link AuthStorage} 中获取标准 API Key</li>
     *   <li>如果以上都失败，返回失败的 CompletableFuture</li>
     * </ol>
     *
     * @param provider 提供商 ID
     * @return 包含 API Key 的 CompletableFuture
     */
    public CompletableFuture<String> getApiKeyForProvider(String provider) {
        // 1. 检查动态提供商是否内置了 API Key（内联 Key 优先级最高）
        ProviderConfig dynProvider = dynamicProviders.get(provider);
        if (dynProvider != null && dynProvider.apiKey() != null) {
            return CompletableFuture.completedFuture(dynProvider.apiKey());
        }

        // 2. 检查是否使用 OAuth 认证，需要时刷新令牌
        if (authStorage.isUsingOAuth(provider)) {
            return authStorage.refreshIfNeeded(provider);
        }

        // 3. 从认证存储中获取标准 API Key
        String key = authStorage.getApiKey(provider);
        if (key != null) {
            return CompletableFuture.completedFuture(key);
        }

        // 4. 所有方式都失败，返回包含异常的 Future
        return CompletableFuture.failedFuture(
            new IllegalStateException("No API key found for provider: " + provider));
    }

    /**
     * 检查指定模型是否使用 OAuth 认证。
     *
     * @param model 要检查的模型
     * @return 如果该模型所属提供商使用 OAuth 认证则返回 true
     */
    public boolean isUsingOAuth(Model model) {
        return authStorage.isUsingOAuth(model.provider());
    }

    // ========== 动态提供商注册（Dynamic Provider Registration） ==========

    /**
     * 注册一个动态提供商。
     *
     * <p>扩展可以通过此方法在运行时注册新的 AI 模型提供商，
     * 无需修改配置文件或重启 Agent。注册的提供商将立即生效，
     * 可在后续的模型查询和 API 调用中使用。</p>
     *
     * @param config 提供商配置，包含 ID、baseUrl、headers、models 等，不能为 null
     * @throws NullPointerException 如果 config 或 config.id() 为 null
     */
    public void registerProvider(ProviderConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(config.id(), "provider id must not be null");
        dynamicProviders.put(config.id(), config);
        log.debug("Registered dynamic provider: {}", config.id());
    }

    /**
     * 注销一个动态提供商。
     *
     * <p>移除之前通过 {@link #registerProvider} 注册的动态提供商，
     * 后续模型查询将不再包含该提供商下的模型。</p>
     *
     * @param providerId 要注销的提供商 ID
     */
    public void unregisterProvider(String providerId) {
        dynamicProviders.remove(providerId);
        log.debug("Unregistered dynamic provider: {}", providerId);
    }

    /**
     * 应用 OAuth 修改到指定提供商的所有模型上。
     *
     * <p>当提供商配置了 OAuth 认证时，API 端点和请求头可能与标准 API Key 模式不同。
     * 该方法注册的修改会在后续的模型查询中自动应用到该提供商下的所有模型上，
     * 覆盖模型的 baseUrl 和 headers。</p>
     *
     * @param provider     提供商 ID
     * @param modification OAuth 修改配置，包含 baseUrl 和 headers
     * @throws NullPointerException 如果 provider 或 modification 为 null
     */
    public void applyOAuthModification(String provider, OAuthModelModification modification) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(modification, "modification must not be null");
        oauthModifications.put(provider, modification);
        log.debug("Applied OAuth modification for provider: {}", provider);
    }

    // ========== 私有辅助方法（Private Helpers） ==========

    /**
     * 从 pi-ai-core 的 ModelRegistry 加载内置模型到本地缓存。
     *
     * <p>遍历所有已注册的提供商，将每个提供商下的模型按
     * provider -> (modelId -> Model) 的结构组织，返回不可变映射。</p>
     *
     * @return 内置模型的不可变映射表
     */
    private Map<String, Map<String, Model>> loadBuiltinModels() {
        Map<String, Map<String, Model>> result = new LinkedHashMap<>();
        for (String provider : ModelRegistry.getProviders()) {
            Map<String, Model> models = new LinkedHashMap<>();
            for (Model m : ModelRegistry.getModels(provider)) {
                models.put(m.id(), m);
            }
            result.put(provider, models);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 从用户提供的 models.json 文件加载自定义模型。
     *
     * <p>文件格式为 JSON：{ providerId: { modelId: Model, ... }, ... }
     * 如果文件不存在或解析失败，仅记录警告日志，不会抛出异常。
     * 加载的模型会与现有自定义模型合并到 {@link #customModels} 中。</p>
     *
     * @param modelsJsonPath models.json 文件的路径
     */
    private void loadCustomModels(String modelsJsonPath) {
        Path path = Path.of(modelsJsonPath);
        // 文件不存在时静默跳过，不视为错误
        if (!Files.exists(path)) {
            log.debug("Custom models.json not found at: {}", modelsJsonPath);
            return;
        }

        try {
            String content = Files.readString(path);
            // 反序列化为 Map<String, Map<String, Model>> 结构
            Map<String, Map<String, Model>> loaded = PiAiJson.MAPPER.readValue(
                content,
                new TypeReference<Map<String, Map<String, Model>>>() {}
            );

            if (loaded != null) {
                for (Map.Entry<String, Map<String, Model>> entry : loaded.entrySet()) {
                    customModels.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
                }
                log.debug("Loaded custom models from: {}", modelsJsonPath);
            }
        } catch (IOException e) {
            // 文件读取或解析失败时仅记录警告，不中断 Agent 初始化
            log.warn("Failed to load custom models from {}: {}", modelsJsonPath, e.getMessage());
        }
    }

    /**
     * 从动态提供商配置中查找指定模型 ID 对应的模型。
     *
     * <p>遍历提供商配置中的模型列表，找到匹配 ID 的模型配置后，
     * 调用 {@link #buildModelFromConfig} 构建完整的 Model 对象。</p>
     *
     * @param provider 动态提供商配置
     * @param modelId  要查找的模型 ID
     * @return 构建的 Model 对象，如果未找到则返回 null
     */
    private Model buildModelFromProvider(ProviderConfig provider, String modelId) {
        if (provider.models() == null) return null;

        for (ProviderModelConfig mc : provider.models()) {
            if (modelId.equals(mc.id())) {
                return buildModelFromConfig(provider, mc);
            }
        }
        return null;
    }

    /**
     * 从提供商配置和模型配置构建完整的 Model 对象。
     *
     * <p>将动态提供商和模型配置中的字段映射到 Model 记录的标准字段。
     * 对于未提供的字段使用合理的默认值：</p>
     * <ul>
     *   <li>cost：默认零费用（0, 0, 0, 0）</li>
     *   <li>name：默认使用模型 ID</li>
     *   <li>api：默认 "openai-completions"（兼容 OpenAI API 格式）</li>
     *   <li>reasoning：默认为 false</li>
     *   <li>input：默认为仅文本 ["text"]</li>
     *   <li>contextWindow：默认 128000</li>
     *   <li>maxTokens：默认 4096</li>
     * </ul>
     *
     * @param provider 动态提供商配置
     * @param mc       模型配置
     * @return 构建完成的 Model 对象
     */
    private Model buildModelFromConfig(ProviderConfig provider, ProviderModelConfig mc) {
        ModelCost cost = mc.cost() != null ? mc.cost() : new ModelCost(0, 0, 0, 0);
        return new Model(
            mc.id(),
            mc.name() != null ? mc.name() : mc.id(),
            "openai-completions",  // 动态提供商默认使用 OpenAI 兼容的 API 格式
            provider.id(),
            provider.baseUrl(),
            mc.reasoning() != null ? mc.reasoning() : false,
            mc.input() != null ? List.of(mc.input().split(",")) : List.of("text"),
            cost,
            mc.contextWindow() != null ? mc.contextWindow() : 128000,
            mc.maxTokens() != null ? mc.maxTokens() : 4096,
            provider.headers(),
            null
        );
    }

    /**
     * 对模型应用 OAuth 修改（如果存在）。
     *
     * <p>检查当前提供商是否有已注册的 OAuth 修改，如果有则：
     * <ul>
     *   <li>合并请求头：保留原模型请求头，使用 OAuth 修改中的请求头覆盖同名字段</li>
     *   <li>覆盖 baseUrl：如果 OAuth 修改中指定了 baseUrl，则替换模型的 baseUrl</li>
     * </ul>
     * </p>
     *
     * @param model 原始模型对象
     * @return 应用了 OAuth 修改后的新 Model 对象，如果没有 OAuth 修改则返回原对象
     */
    private Model applyOAuthModification(Model model) {
        OAuthModelModification mod = oauthModifications.get(model.provider());
        if (mod == null) return model;

        // 合并请求头：原模型请求头 + OAuth 修改中的请求头（OAuth 优先级更高）
        Map<String, String> mergedHeaders = new LinkedHashMap<>();
        if (model.headers() != null) mergedHeaders.putAll(model.headers());
        if (mod.headers() != null) mergedHeaders.putAll(mod.headers());

        return new Model(
            model.id(), model.name(), model.api(), model.provider(),
            mod.baseUrl() != null ? mod.baseUrl() : model.baseUrl(),
            model.reasoning(), model.input(), model.cost(),
            model.contextWindow(), model.maxTokens(),
            mergedHeaders.isEmpty() ? null : mergedHeaders,
            model.compat()
        );
    }
}
