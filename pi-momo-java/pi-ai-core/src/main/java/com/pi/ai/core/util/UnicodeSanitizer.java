package com.pi.ai.core.util;

/**
 * Unicode 代理对（Surrogate Pair）清理工具，移除字符串中未配对的高/低代理字符。
 *
 * <p>Java 的 String 内部使用 UTF-16 编码存储字符。在 UTF-16 中：
 * <ul>
 *   <li>基本多文种平面（BMP）的字符（U+0000 到 U+FFFF）直接用一个 char 表示</li>
 *   <li>辅助平面字符（U+10000 以上，如 emoji 𐀀 等）用两个 char 表示：
 *       <b>高代理</b>（U+D800-U+DBFF）+ <b>低代理</b>（U+DC00-U+DFFF）</li>
 * </ul>
 *
 * <p>问题场景：当 LLM 返回的文本中包含未配对的代理字符时（例如字符串被截断、
 * 乱码或模型生成错误），Jackson 在 JSON 序列化时会抛出异常，因为未配对的代理字符
 * 不是合法的 UTF-16 编码序列。
 *
 * <p>处理规则：
 * <ul>
 *   <li>正确配对的代理对（高代理后紧跟低代理）→ 保留，不影响 emoji 等合法字符</li>
 *   <li>未配对的高代理（后面没有低代理）→ 移除</li>
 *   <li>未配对的低代理（前面没有高代理）→ 移除</li>
 *   <li>普通 BMP 字符 → 保留</li>
 * </ul>
 *
 * <p>对应 TypeScript 中的 {@code utils/sanitize-unicode.ts}。
 */
public final class UnicodeSanitizer {

    private UnicodeSanitizer() {
        // 工具类，禁止实例化
    }

    /**
     * 移除字符串中未配对的 Unicode 代理字符。
     *
     * <p>使用延迟初始化（lazy initialization）的 StringBuilder 优化性能：
     * 大多数字符串不包含任何代理字符，此时直接返回原字符串，避免内存分配。
     * 只有当检测到未配对的代理字符时，才创建 StringBuilder 并复制已有内容。
     *
     * <p>算法：
     * <ol>
     *   <li>遍历字符串中的每个 char</li>
     *   <li>遇到高代理时，检查下一个 char 是否为低代理：
     *       - 如果是，保留两个字符（正确配对），并跳过低代理</li>
     *       - 如果不是，移除高代理</li>
     *   <li>遇到低代理时，说明前面没有高代理，直接移除</li>
     *   <li>普通字符直接保留</li>
     * </ol>
     *
     * @param text 输入文本，可能包含未配对的代理字符
     * @return 清理后的文本，所有未配对的代理字符已被移除。null 输入返回 null
     */
    public static String sanitizeSurrogates(String text) {
        // ========== 前置检查：null 输入返回 null ==========
        if (text == null) {
            return null;
        }

        int len = text.length();
        // 延迟初始化 StringBuilder：只在检测到需要清理时创建
        // 性能优化：大多数字符串不含代理字符，无需分配 StringBuilder
        StringBuilder sb = null;

        // ========== 遍历每个字符，检测并处理代理字符 ==========
        for (int i = 0; i < len; i++) {
            char ch = text.charAt(i);

            if (Character.isHighSurrogate(ch)) {
                // ---- 情况 1：高代理（U+D800-U+DBFF）----
                // 检查下一个字符是否为低代理
                if (i + 1 < len && Character.isLowSurrogate(text.charAt(i + 1))) {
                    // 子情况 1a：正确配对的代理对（如 emoji）
                    // 保留两个字符
                    if (sb != null) {
                        sb.append(ch);
                        sb.append(text.charAt(i + 1));
                    }
                    i++; // 跳过低代理，避免下次循环重复处理
                } else {
                    // 子情况 1b：未配对的高代理（后面没有低代理）
                    // 移除它：首次遇到时初始化 StringBuilder 并复制之前的内容
                    if (sb == null) {
                        sb = new StringBuilder(len);
                        sb.append(text, 0, i); // 复制当前位置之前的所有字符
                    }
                    // 跳过此高代理字符（不添加到 sb 中）
                }
            } else if (Character.isLowSurrogate(ch)) {
                // ---- 情况 2：低代理（U+DC00-U+DFFF）----
                // 前面没有高代理，属于未配对的低代理，移除它
                if (sb == null) {
                    sb = new StringBuilder(len);
                    sb.append(text, 0, i); // 复制当前位置之前的所有字符
                }
                // 跳过此低代理字符（不添加到 sb 中）
            } else {
                // ---- 情况 3：普通 BMP 字符（U+0000-U+D7FF 或 U+E000-U+FFFF）----
                // 不在代理区范围内，直接保留
                if (sb != null) {
                    sb.append(ch);
                }
            }
        }

        // ========== 返回结果 ==========
        // 如果从未创建 StringBuilder，说明字符串没有未配对的代理字符，直接返回原字符串
        // 否则返回清理后的字符串
        return sb != null ? sb.toString() : text;
    }
}