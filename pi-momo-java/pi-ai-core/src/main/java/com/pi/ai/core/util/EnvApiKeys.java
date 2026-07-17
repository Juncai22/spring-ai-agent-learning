package com.pi.ai.core.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 从环境变量获取 API Key 的工具类。
 *
 * <p>支持 20 个以上 Provider 的环境变量映射，包括特殊的多变量优先级逻辑：
 * <ul>
 *   <li>通用 Provider 使用 ENV_MAP 直接映射到单一环境变量</li>
 *   <li>Anthropic 优先使用 ANTHROPIC_OAUTH_TOKEN（OAuth 令牌），回退到 ANTHROPIC_API_KEY</li>
 *   <li>GitHub Copilot 依次检查 COPILOT_GITHUB_TOKEN、GH_TOKEN、GITHUB_TOKEN 三个变量</li>
 *   <li>Google Vertex AI 支持 API Key 或 ADC（Application Default Credentials）两种认证方式</li>
 *   <li>Amazon Bedrock 支持多种 AWS 认证方式（Profile、AK/SK、Bearer Token、Container 凭证、Web Identity）</li>
 * </ul>
 *
 * <p>对应 TypeScript 中的 {@code env-api-keys.ts}。
 */
public final class EnvApiKeys {

    /** 通用 Provider 名称到环境变量名的映射表 — 一对一映射，无回退逻辑 */
    private static final Map<String, String> ENV_MAP = Map.ofEntries(
            Map.entry("openai", "OPENAI_API_KEY"),                     // OpenAI
            Map.entry("azure-openai-responses", "AZURE_OPENAI_API_KEY"), // Azure OpenAI Responses API
            Map.entry("google", "GEMINI_API_KEY"),                      // Google Gemini
            Map.entry("groq", "GROQ_API_KEY"),                          // Groq
            Map.entry("cerebras", "CEREBRAS_API_KEY"),                  // Cerebras
            Map.entry("xai", "XAI_API_KEY"),                            // xAI (Grok)
            Map.entry("openrouter", "OPENROUTER_API_KEY"),              // OpenRouter
            Map.entry("vercel-ai-gateway", "AI_GATEWAY_API_KEY"),       // Vercel AI Gateway
            Map.entry("zai", "ZAI_API_KEY"),                            // z.ai
            Map.entry("mistral", "MISTRAL_API_KEY"),                    // Mistral AI
            Map.entry("minimax", "MINIMAX_API_KEY"),                    // MiniMax (海外)
            Map.entry("minimax-cn", "MINIMAX_CN_API_KEY"),              // MiniMax (国内)
            Map.entry("huggingface", "HF_TOKEN"),                       // Hugging Face
            Map.entry("opencode", "OPENCODE_API_KEY"),                  // OpenCode
            Map.entry("opencode-go", "OPENCODE_API_KEY"),               // OpenCode Go
            Map.entry("kimi-coding", "KIMI_API_KEY")                    // Kimi Coding
    );

    /** 缓存 Vertex ADC 凭证检测结果，避免重复文件系统访问 */
    private static volatile Boolean cachedVertexAdcCredentialsExists;

    private EnvApiKeys() {
        // 工具类，禁止实例化
    }

    /**
     * 根据 provider 名称从环境变量获取 API Key。
     *
     * <p>特殊处理逻辑：
     * <ul>
     *   <li><b>github-copilot</b>：依次检查 COPILOT_GITHUB_TOKEN、GH_TOKEN、GITHUB_TOKEN，
     *       返回第一个非空值</li>
     *   <li><b>anthropic</b>：优先使用 ANTHROPIC_OAUTH_TOKEN（OAuth 令牌），
     *       回退到 ANTHROPIC_API_KEY（传统 API Key）</li>
     *   <li><b>google-vertex</b>：先检查 GOOGLE_CLOUD_API_KEY，如果不存在则检查是否配置了
     *       ADC 凭证（需要同时有 GOOGLE_APPLICATION_CREDENTIALS 或默认 ADC 文件、
     *       GOOGLE_CLOUD_PROJECT 或 GCLOUD_PROJECT、GOOGLE_CLOUD_LOCATION 三个条件）</li>
     *   <li><b>amazon-bedrock</b>：检查 AWS_PROFILE、AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY、
     *       AWS_BEARER_TOKEN_BEDROCK、AWS_CONTAINER_CREDENTIALS_RELATIVE_URI/FULL_URI、
     *       AWS_WEB_IDENTITY_TOKEN_FILE 任一存在即视为已认证</li>
     *   <li>其他 Provider：直接从 ENV_MAP 映射表中查找对应的环境变量名并读取</li>
     * </ul>
     *
     * @param provider 服务提供商标识（如 "openai"、"anthropic"、"github-copilot" 等）
     * @return API Key 字符串，未找到对应的环境变量时返回 null
     */
    public static String getEnvApiKey(String provider) {
        // 【前置检查】provider 为 null 时直接返回 null
        if (provider == null) {
            return null;
        }

        // ========== 根据 Provider 类型分发到不同的环境变量解析逻辑 ==========
        return switch (provider) {
            // --- GitHub Copilot：三级回退，依次检查三个 token 变量 ---
            // 优先级：COPILOT_GITHUB_TOKEN > GH_TOKEN > GITHUB_TOKEN
            case "github-copilot" -> firstNonEmpty(
                    getEnv("COPILOT_GITHUB_TOKEN"),  // 第一优先级：Copilot 专用 GitHub Token
                    getEnv("GH_TOKEN"),              // 第二优先级：GitHub CLI Token
                    getEnv("GITHUB_TOKEN")           // 第三优先级：通用 GitHub Token
            );
            // --- Anthropic：优先 OAuth 令牌，回退到 API Key ---
            // OAuth 令牌作用域更细粒度，API Key 是传统方式
            case "anthropic" -> firstNonEmpty(
                    getEnv("ANTHROPIC_OAUTH_TOKEN"), // 第一优先级：OAuth 令牌
                    getEnv("ANTHROPIC_API_KEY")      // 第二优先级：传统 API Key
            );
            // --- Google Vertex AI：API Key 或 ADC 凭证二选一 ---
            case "google-vertex" -> resolveVertexApiKey();
            // --- Amazon Bedrock：多种 AWS 认证方式 ---
            case "amazon-bedrock" -> resolveBedrockApiKey();
            // --- 通用 Provider：从映射表获取环境变量名并读取值 ---
            default -> {
                String envVar = ENV_MAP.get(provider); // 从映射表查找环境变量名
                yield envVar != null ? getEnv(envVar) : null; // 查到则读取，查不到返回 null
            }
        };
    }

    /**
     * 解析 Google Vertex AI 的 API Key 或 ADC（Application Default Credentials）凭证。
     *
     * <p>解析顺序：
     * <ol>
     *   <li>优先使用 GOOGLE_CLOUD_API_KEY 环境变量</li>
     *   <li>如果 API Key 不存在，检查是否配置了 ADC 凭证，需要同时满足三个条件：
     *       - 存在 ADC 凭证文件（GOOGLE_APPLICATION_CREDENTIALS 指定或默认路径）
     *       - 设置了 GOOGLE_CLOUD_PROJECT 或 GCLOUD_PROJECT
     *       - 设置了 GOOGLE_CLOUD_LOCATION</li>
     * </ol>
     *
     * @return API Key 字符串，或 {@code "<authenticated>"} 表示已通过 ADC 方式认证，无可用凭证时返回 null
     */
    private static String resolveVertexApiKey() {
        // ========== 分支 1：优先使用 API Key ==========
        String cloudApiKey = getEnv("GOOGLE_CLOUD_API_KEY");
        if (cloudApiKey != null) {
            return cloudApiKey; // 有 API Key 直接返回
        }

        // ========== 分支 2：回退到 ADC 凭证方式 ==========
        // ADC 方式需要同时满足三个条件：
        // 条件 A：存在 ADC 凭证文件（环境变量指定或默认路径）
        boolean hasCredentials = hasVertexAdcCredentials();
        // 条件 B：已设置 GCP 项目 ID（两个环境变量二选一）
        boolean hasProject = getEnv("GOOGLE_CLOUD_PROJECT") != null || getEnv("GCLOUD_PROJECT") != null;
        // 条件 C：已设置 GCP 区域/位置
        boolean hasLocation = getEnv("GOOGLE_CLOUD_LOCATION") != null;

        if (hasCredentials && hasProject && hasLocation) {
            // 三个条件全部满足，返回占位符表示已通过 ADC 方式认证
            // 实际凭证由底层 SDK 自动处理（从文件读取）
            return "<authenticated>";
        }
        // 既无 API Key 也无 ADC 凭证
        return null;
    }

    /**
     * 解析 Amazon Bedrock 的多种认证方式。
     *
     * <p>检查以下任一认证方式是否可用：
     * <ul>
     *   <li>AWS_PROFILE：使用命名 Profile</li>
     *   <li>AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY：使用 AK/SK</li>
     *   <li>AWS_BEARER_TOKEN_BEDROCK：使用 Bearer Token</li>
     *   <li>AWS_CONTAINER_CREDENTIALS_RELATIVE_URI 或 FULL_URI：ECS/EKS 容器凭证</li>
     *   <li>AWS_WEB_IDENTITY_TOKEN_FILE：Web Identity Federation（如 EKS IRSA）</li>
     * </ul>
     * 任一认证方式存在即视为已配置，返回占位符。实际认证由 AWS SDK 自动处理。
     *
     * @return {@code "<authenticated>"} 表示至少一种认证方式可用，否则返回 null
     */
    private static String resolveBedrockApiKey() {
        // ========== 检查 5 种 AWS 认证方式，任一可用即返回 ==========
        if (getEnv("AWS_PROFILE") != null
                // 方式 1：AWS Profile — 使用 ~/.aws/credentials 或 ~/.aws/config 中的命名 Profile
                // 方式 2：AK/SK 配对 — 需要同时有 Access Key ID 和 Secret Access Key
                || (getEnv("AWS_ACCESS_KEY_ID") != null && getEnv("AWS_SECRET_ACCESS_KEY") != null)
                // 方式 3：Bearer Token — Bedrock 专用的 Bearer Token
                || getEnv("AWS_BEARER_TOKEN_BEDROCK") != null
                // 方式 4：容器凭证 — ECS 使用 RELATIVE_URI，EKS 使用 FULL_URI
                || getEnv("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI") != null
                || getEnv("AWS_CONTAINER_CREDENTIALS_FULL_URI") != null
                // 方式 5：Web Identity — EKS IRSA / 其他 OIDC 联邦身份
                || getEnv("AWS_WEB_IDENTITY_TOKEN_FILE") != null) {
            return "<authenticated>"; // 至少一种认证方式可用
        }
        return null; // 没有任何认证方式配置
    }

    /**
     * 检测 Google Vertex ADC 凭证文件是否存在。
     *
     * <p>首次检测后缓存结果（cachedVertexAdcCredentialsExists），避免重复的文件系统访问。
     * 检测顺序：
     * <ol>
     *   <li>检查 GOOGLE_APPLICATION_CREDENTIALS 环境变量指定的文件是否存在</li>
     *   <li>如果未设置该环境变量，检查默认路径 {@code ~/.config/gcloud/application_default_credentials.json}</li>
     * </ol>
     *
     * @return true 如果 ADC 凭证文件存在
     */
    private static boolean hasVertexAdcCredentials() {
        // ========== 双重检查锁定模式（DCL）的变体：volatile + 非空检查 ==========
        // 使用 volatile 变量保证跨线程可见性，非空检查替代同步锁（因为读多写少）
        if (cachedVertexAdcCredentialsExists == null) {
            // 首次检测：检查 GOOGLE_APPLICATION_CREDENTIALS 环境变量指定的凭证文件
            String gacPath = getEnv("GOOGLE_APPLICATION_CREDENTIALS");
            if (gacPath != null) {
                // 环境变量已设置，检查该路径文件是否存在
                cachedVertexAdcCredentialsExists = Files.exists(Path.of(gacPath));
            } else {
                // 环境变量未设置，回退到 gcloud CLI 的默认 ADC 路径
                String home = System.getProperty("user.home");
                if (home != null) {
                    // 默认路径：~/.config/gcloud/application_default_credentials.json
                    Path defaultPath = Path.of(home, ".config", "gcloud", "application_default_credentials.json");
                    cachedVertexAdcCredentialsExists = Files.exists(defaultPath);
                } else {
                    // 无法获取用户主目录（极端情况），认为凭证不存在
                    cachedVertexAdcCredentialsExists = false;
                }
            }
        }
        // 返回缓存结果（首次调用后不再重复文件系统访问）
        return cachedVertexAdcCredentialsExists;
    }

    /**
     * 获取环境变量值，空字符串视为 null。
     *
     * <p>某些系统在未设置环境变量时返回空字符串而非 null，此方法统一处理为 null。
     *
     * @param name 环境变量名
     * @return 环境变量值，未设置或为空字符串时返回 null
     */
    static String getEnv(String name) {
        String value = System.getenv(name);
        // 空字符串统一视为未设置（某些平台行为差异）
        return (value != null && !value.isEmpty()) ? value : null;
    }

    /**
     * 返回第一个非 null 的值。
     *
     * <p>用于实现多级回退优先级逻辑：按顺序检查每个值，返回第一个非 null 的结果。
     *
     * @param values 按优先级顺序排列的值列表
     * @return 第一个非 null 的值，全部为 null 时返回 null
     */
    private static String firstNonEmpty(String... values) {
        // 按优先级顺序遍历，返回第一个非 null 值
        for (String v : values) {
            if (v != null) {
                return v; // 找到有效值，立即返回
            }
        }
        return null; // 所有值均为 null
    }
}