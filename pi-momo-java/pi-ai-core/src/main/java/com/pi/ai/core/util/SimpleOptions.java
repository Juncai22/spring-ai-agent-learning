package com.pi.ai.core.util;

import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.SimpleStreamOptions;
import com.pi.ai.core.types.StreamOptions;
import com.pi.ai.core.types.ThinkingBudgets;
import com.pi.ai.core.types.ThinkingLevel;

/**
 * 选项构建工具，用于从简化的 {@link SimpleStreamOptions} 构建完整的 {@link StreamOptions}。
 *
 * <p>主要功能：
 * <ul>
 *   <li>将用户友好的简单选项转换为完整的 StreamOptions 对象</li>
 *   <li>根据思考级别（ThinkingLevel）自动调整 maxTokens 和 thinkingBudget</li>
 *   <li>处理思考级别降级（xhigh → high）</li>
 *   <li>确保 maxTokens 和 thinkingBudget 的合理分配，保留最小输出 token 空间</li>
 * </ul>
 *
 * <p>对应 TypeScript 中的 {@code simple-options.ts}。
 */
public final class SimpleOptions {

    /** 各思考级别对应的默认 token 预算 */
    private static final int DEFAULT_MINIMAL = 1024;   // 最低思考量：1K tokens
    private static final int DEFAULT_LOW = 2048;        // 低思考量：2K tokens
    private static final int DEFAULT_MEDIUM = 8192;     // 中等思考量：8K tokens
    private static final int DEFAULT_HIGH = 16384;      // 高思考量：16K tokens

    /** 最小输出 token 数，确保模型有足够的空间生成可见输出 */
    private static final int MIN_OUTPUT_TOKENS = 1024;

    private SimpleOptions() {
        // 工具类，禁止实例化
    }

    /**
     * 从 SimpleStreamOptions 构建基础 StreamOptions。
     *
     * <p>此方法将简化的用户选项映射到完整的 StreamOptions 对象：
     * <ul>
     *   <li>maxTokens 默认值为模型最大 token 数和 32000 中的较小值</li>
     *   <li>apiKey 参数优先于 options 中设置的 apiKey</li>
     *   <li>其他选项（temperature、signal、sessionId 等）直接透传</li>
     * </ul>
     *
     * @param model   模型定义，用于获取模型的最大 token 限制
     * @param options 简化选项，可为 null（此时使用默认值）
     * @param apiKey  API Key，可为 null（此时使用 options 中的 apiKey）
     * @return 构建好的 StreamOptions 对象
     */
    public static StreamOptions buildBaseOptions(Model model, SimpleStreamOptions options, String apiKey) {
        // ========== 计算 maxTokens ==========
        // 优先级：options 中的 maxTokens > 模型限制和 32000 的较小值（默认值）
        int maxTokens = (options != null && options.getMaxTokens() != null)
                ? options.getMaxTokens()          // 用户显式指定了 maxTokens
                : Math.min(model.maxTokens(), 32000); // 默认：取模型限制和 32000 中较小的

        // ========== 构建 StreamOptions ==========
        var builder = StreamOptions.builder()
                .maxTokens(maxTokens);

        if (options != null) {
            // 透传所有可选参数，保持原始语义不变
            builder.temperature(options.getTemperature())       // 采样温度（0~2），控制随机性，值越高多样性越大
                    .signal(options.getSignal())                 // 取消信号（CompletableFuture），用于中断正在进行的请求
                    .cacheRetention(options.getCacheRetention()) // 缓存保留策略（prompt caching 的 TTL 配置）
                    .sessionId(options.getSessionId())           // 会话 ID，用于多轮对话的上下文关联
                    .headers(options.getHeaders())               // 自定义 HTTP 请求头
                    .onPayload(options.getOnPayload())           // 流式响应每帧回调函数
                    .maxRetryDelayMs(options.getMaxRetryDelayMs()) // 最大重试延迟（毫秒），指数退避上限
                    .metadata(options.getMetadata());             // 元数据，透传给 Provider
        }

        // ========== 解析 API Key ==========
        // apiKey 参数优先于 options 中的 apiKey，实现调用方覆盖
        // 规则：方法参数 > options 中的设置 > null
        String resolvedApiKey = apiKey != null ? apiKey : (options != null ? options.getApiKey() : null);
        builder.apiKey(resolvedApiKey);

        return builder.build();
    }

    /**
     * 将 xhigh 思考级别降级为 high，其他级别保持不变。
     *
     * <p>某些 Provider 或模型不支持 xhigh 思考级别，
     * 此方法将 xhigh 统一降级为 high，确保兼容性。
     *
     * @param effort 思考级别，可为 null
     * @return 降级后的思考级别，null 输入返回 null
     */
    public static ThinkingLevel clampReasoning(ThinkingLevel effort) {
        if (effort == ThinkingLevel.XHIGH) {
            return ThinkingLevel.HIGH; // xhigh 不支持，降级为 high
        }
        return effort; // 其他级别保持不变
    }

    /**
     * 根据思考级别调整 maxTokens 和 thinkingBudget。
     *
     * <p>启用思考（reasoning/thinking）时，模型需要额外 token 用于内部思考过程。
     * 此方法根据思考级别计算适当的 thinkingBudget，并相应增加 maxTokens：
     * <ul>
     *   <li>思考预算从默认值或自定义预算中获取</li>
     *   <li>maxTokens = 基础 maxTokens + thinkingBudget，受模型最大限制</li>
     *   <li>如果调整后的 maxTokens 小于等于 thinkingBudget，则压缩 thinkingBudget
     *       以保留至少 MIN_OUTPUT_TOKENS 个输出 token 空间</li>
     * </ul>
     *
     * @param baseMaxTokens  基础 maxTokens（不含思考预算）
     * @param modelMaxTokens 模型最大 token 数上限
     * @param reasoningLevel 思考级别
     * @param customBudgets  自定义预算，可为 null（使用默认值）
     * @return 包含调整后的 maxTokens 和 thinkingBudget 的 ThinkingResult
     */
    public static ThinkingResult adjustMaxTokensForThinking(
            int baseMaxTokens,
            int modelMaxTokens,
            ThinkingLevel reasoningLevel,
            ThinkingBudgets customBudgets
    ) {
        // ========== 步骤 1：合并默认预算和自定义预算 ==========
        // 自定义值优先，未设置时使用默认值（null 安全）
        int minimal = customBudgets != null && customBudgets.minimal() != null ? customBudgets.minimal() : DEFAULT_MINIMAL;
        int low = customBudgets != null && customBudgets.low() != null ? customBudgets.low() : DEFAULT_LOW;
        int medium = customBudgets != null && customBudgets.medium() != null ? customBudgets.medium() : DEFAULT_MEDIUM;
        int high = customBudgets != null && customBudgets.high() != null ? customBudgets.high() : DEFAULT_HIGH;

        // ========== 步骤 2：根据思考级别确定思考预算 ==========
        // 先降级 xhigh（如果存在），再获取对应的思考预算
        ThinkingLevel level = clampReasoning(reasoningLevel);
        int thinkingBudget = switch (level) {
            case MINIMAL -> minimal;  // 最低思考级别：1K tokens
            case LOW -> low;          // 低思考级别：2K tokens
            case MEDIUM -> medium;    // 中等思考级别：8K tokens
            case HIGH -> high;        // 高思考级别：16K tokens
            case XHIGH -> high;       // 不应到达此分支，clampReasoning 已提前处理
        };

        // ========== 步骤 3：计算新的 maxTokens ==========
        // 新 maxTokens = 基础值 + 思考预算，但不能超过模型限制
        int maxTokens = Math.min(baseMaxTokens + thinkingBudget, modelMaxTokens);

        // ========== 步骤 4：保护机制 — 确保思考预算不会挤占全部输出空间 ==========
        // 如果 maxTokens 小于等于 thinkingBudget，说明模型限制太紧（例如模型只有 2K 上限），
        // 需要压缩思考预算以保留至少 MIN_OUTPUT_TOKENS 个输出 token 空间
        if (maxTokens <= thinkingBudget) {
            thinkingBudget = Math.max(0, maxTokens - MIN_OUTPUT_TOKENS);
        }

        return new ThinkingResult(maxTokens, thinkingBudget);
    }

    /**
     * adjustMaxTokensForThinking 方法的返回值封装。
     *
     * @param maxTokens      调整后的 maxTokens（基础值 + 思考预算，受模型上限限制）
     * @param thinkingBudget 计算得出的思考 token 预算
     */
    public record ThinkingResult(int maxTokens, int thinkingBudget) { }
}