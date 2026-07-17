package com.pi.ai.core.util;

import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.StopReason;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 上下文溢出检测工具，用于检测 15 种以上 Provider 特定的溢出错误模式。
 *
 * <p>上下文溢出（Context Overflow）是指 LLM 请求的输入 token 数量超过了模型的最大上下文窗口大小。
 * 不同 Provider 返回溢出错误的方式各不相同，本工具统一处理以下两种情况：
 * <ol>
 *   <li><b>错误型溢出</b>：大多数 Provider 返回 stopReason=error 和特定的错误消息文本。
 *       通过预定义的正则表达式模式列表匹配这些错误消息。</li>
 *   <li><b>静默溢出</b>：部分 Provider（如 z.ai）会接受超出上下文窗口的请求并正常返回结果，
 *       但实际输入的 token 数超过了 contextWindow 限制。通过比较 usage 统计与 contextWindow 来检测。</li>
 * </ol>
 *
 * <p>对应 TypeScript 中的 {@code utils/overflow.ts}。
 */
public final class ContextOverflow {

    /** Provider 特定的溢出错误模式列表 — 覆盖 15+ 种 Provider 的错误消息格式 */
    private static final List<Pattern> OVERFLOW_PATTERNS = List.of(
            // OpenAI / Azure OpenAI：提示词过长
            Pattern.compile("prompt is too long", Pattern.CASE_INSENSITIVE),
            // 当前模型输入过长
            Pattern.compile("input is too long for requested model", Pattern.CASE_INSENSITIVE),
            // Anthropic / 其他：超出上下文窗口
            Pattern.compile("exceeds the context window", Pattern.CASE_INSENSITIVE),
            // 输入 token 数超出最大限制
            Pattern.compile("input token count.*exceeds the maximum", Pattern.CASE_INSENSITIVE),
            // 最大提示词长度限制（数字可变的模式）
            Pattern.compile("maximum prompt length is \\d+", Pattern.CASE_INSENSITIVE),
            // 建议减少消息长度
            Pattern.compile("reduce the length of the messages", Pattern.CASE_INSENSITIVE),
            // 最大上下文长度限制（数字可变的模式）
            Pattern.compile("maximum context length is \\d+ tokens", Pattern.CASE_INSENSITIVE),
            // 超出某个具体数字限制
            Pattern.compile("exceeds the limit of \\d+", Pattern.CASE_INSENSITIVE),
            // 超出可用上下文大小
            Pattern.compile("exceeds the available context size", Pattern.CASE_INSENSITIVE),
            // 大于上下文长度
            Pattern.compile("greater than the context length", Pattern.CASE_INSENSITIVE),
            // 上下文窗口超出限制
            Pattern.compile("context window exceeds limit", Pattern.CASE_INSENSITIVE),
            // 超出了模型 token 限制
            Pattern.compile("exceeded model token limit", Pattern.CASE_INSENSITIVE),
            // 对具有特定最大上下文长度的模型来说太大
            Pattern.compile("too large for model with \\d+ maximum context length", Pattern.CASE_INSENSITIVE),
            // 内部错误码格式
            Pattern.compile("model_context_window_exceeded", Pattern.CASE_INSENSITIVE),
            // 下划线或空格分隔的上下文长度超限（兼容多种命名风格）
            Pattern.compile("context[_ ]length[_ ]exceeded", Pattern.CASE_INSENSITIVE),
            // token 数过多
            Pattern.compile("too many tokens", Pattern.CASE_INSENSITIVE),
            // token 限制超限
            Pattern.compile("token limit exceeded", Pattern.CASE_INSENSITIVE)
    );

    /** Cerebras 特殊模式：返回 400 或 413 状态码且无响应体的情况 */
    private static final Pattern CEREBRAS_PATTERN =
            Pattern.compile("^4(00|13)\\s*(status code)?\\s*\\(no body\\)", Pattern.CASE_INSENSITIVE);

    private ContextOverflow() {
        // 工具类，禁止实例化
    }

    /**
     * 检测助手消息是否表示上下文溢出错误。
     *
     * <p>检测逻辑分为两步：
     * <ol>
     *   <li>如果 stopReason 为 ERROR，则在错误消息中匹配预定义的溢出模式列表；
     *       同时针对 Cerebras 的特殊错误格式做额外匹配。</li>
     *   <li>如果 stopReason 为 STOP（正常结束），但 usage 统计显示输入 token 数
     *       （包括缓存读取）超过了 contextWindow，则判定为静默溢出。</li>
     * </ol>
     *
     * @param message       助手消息，包含 stopReason、错误消息和 usage 统计
     * @param contextWindow 模型上下文窗口大小（用于检测静默溢出，传 0 或负数跳过此检测）
     * @return 是否为上下文溢出
     */
    public static boolean isContextOverflow(AssistantMessage message, int contextWindow) {
        // 【前置检查】消息为空时直接返回 false，避免 NullPointerException
        if (message == null) {
            return false;
        }

        // ========== Case 1: 错误型溢出检测 ==========
        // 条件：stopReason 为 ERROR 且存在错误消息文本
        if (message.getStopReason() == StopReason.ERROR && message.getErrorMessage() != null) {
            String errorMsg = message.getErrorMessage();

            // 遍历所有已知溢出模式，匹配任一即判定为溢出
            for (Pattern pattern : OVERFLOW_PATTERNS) {
                if (pattern.matcher(errorMsg).find()) {
                    return true; // 匹配到已知溢出模式
                }
            }

            // Cerebras 特殊处理：该 Provider 在溢出时返回 400/413 状态码且无响应体
            if (CEREBRAS_PATTERN.matcher(errorMsg).find()) {
                return true;
            }
        }

        // ========== Case 2: 静默溢出检测（z.ai 风格）==========
        // 条件：contextWindow > 0（传入了有效值）且 stopReason 为 STOP（正常返回）且 usage 统计存在
        if (contextWindow > 0 && message.getStopReason() == StopReason.STOP && message.getUsage() != null) {
            // 输入 token = 用户输入 + 缓存读取（prompt caching 部分）
            int inputTokens = message.getUsage().input() + message.getUsage().cacheRead();
            if (inputTokens > contextWindow) {
                // 实际输入 token 数超过了上下文窗口限制，认定为静默溢出
                return true;
            }
        }

        // 两种检测均未命中，判定为非溢出
        return false;
    }

    /**
     * 返回溢出模式列表，供测试用例验证模式匹配的完整性。
     *
     * @return 不可修改的溢出模式正则表达式列表
     */
    public static List<Pattern> getOverflowPatterns() {
        return OVERFLOW_PATTERNS;
    }
}