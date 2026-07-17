package com.pi.ai.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.Collections;
import java.util.Map;

/**
 * 部分 JSON 增量解析器，用于流式传输中解析不完整的 JSON 片段。
 *
 * <p>在 LLM 流式响应中，JSON 数据是分块到达的，每个块可能是一个不完整的 JSON 片段。
 * 此解析器能够安全地处理这些不完整片段，始终返回有效结果而不抛出异常。
 *
 * <p>解析策略（按优先级降序）：
 * <ol>
 *   <li>null/空字符串 → 直接返回空 Map</li>
 *   <li>标准 Jackson 解析 — 如果 JSON 完整，这是最快的解析路径</li>
 *   <li>容错解析 — 尝试补全不完整的 JSON（补全未关闭的括号、引号，移除悬挂逗号等）</li>
 *   <li>全部失败 → 返回空 Map，确保调用方不会因解析异常而崩溃</li>
 * </ol>
 *
 * <p>对应 TypeScript 中的 {@code utils/json-parse.ts}。
 */
public final class StreamingJsonParser {

    private StreamingJsonParser() {
        // 工具类，禁止实例化
    }

    /**
     * 解析可能不完整的 JSON 字符串。
     *
     * <p>此方法是入口点，实现了三级解析策略：
     * <ol>
     *   <li>先尝试标准 Jackson 解析（O(n) 复杂度，最快路径）</li>
     *   <li>标准解析失败时，调用 repairPartialJson 修复后再次尝试</li>
     *   <li>修复后仍失败时，返回空 Map 保证调用方安全</li>
     * </ol>
     *
     * @param partialJson 可能不完整的 JSON 字符串
     * @return 解析结果为 {@code Map<String, Object>}，解析失败时返回空 Map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseStreamingJson(String partialJson) {
        // ========== 第一级：null/空字符串检查 ==========
        // 流式传输中可能收到空块或 null，直接返回空 Map 避免后续解析异常
        if (partialJson == null || partialJson.trim().isEmpty()) {
            return Collections.emptyMap();
        }

        // ========== 第二级：标准 Jackson 解析（最快路径，适用于完整 JSON）==========
        try {
            // 使用全局 ObjectMapper 直接解析为 Java 对象
            Object result = PiAiJson.MAPPER.readValue(partialJson, Object.class);
            if (result instanceof Map) {
                // 成功解析为 Map 类型，直接返回
                return (Map<String, Object>) result;
            }
            // 结果是数组或其他类型（如字符串、数字），不是期望的 Map 类型
            return Collections.emptyMap();
        } catch (JsonProcessingException e) {
            // 标准解析失败，进入容错解析流程
            // 注意：此处故意 catch 后不处理，继续执行下面的容错逻辑
        }

        // ========== 第三级：容错解析 — 修复不完整的 JSON 后再次尝试 ==========
        try {
            // 调用 repairPartialJson 尝试补全不完整的 JSON 片段
            String repaired = repairPartialJson(partialJson);
            if (repaired != null) {
                // 修复成功，使用 Jackson 解析修复后的 JSON
                Object result = PiAiJson.MAPPER.readValue(repaired, Object.class);
                if (result instanceof Map) {
                    return (Map<String, Object>) result;
                }
            }
        } catch (JsonProcessingException e) {
            // 容错解析也失败，返回空 Map
        }

        // ========== 第四级：全部失败，返回空 Map 保证安全 ==========
        // 所有解析策略均失败，返回空 Map 确保调用方不会因解析异常而崩溃
        return Collections.emptyMap();
    }

    /**
     * 尝试修复不完整的 JSON 字符串。
     *
     * <p>修复算法使用简单的状态机追踪 JSON 结构：
     * <ul>
     *   <li>追踪当前是否在字符串内（inString）</li>
     *   <li>追踪字符串中的转义字符（escaped）</li>
     *   <li>使用栈（bracketStack）追踪未关闭的括号/花括号</li>
     *   <li>在末尾补全未关闭的括号/花括号</li>
     *   <li>移除末尾的悬挂逗号和冒号</li>
     *   <li>如果字符串在字符串内部截断，先补全双引号</li>
     * </ul>
     *
     * <p>注意：此方法仅处理简单情况，无法修复所有不合法 JSON。
     * 对于嵌套结构复杂或深度截断的情况，返回 null 表示无法修复。
     *
     * @param partial 不完整的 JSON 字符串
     * @return 修复后的合法 JSON 字符串，无法修复时返回 null
     */
    static String repairPartialJson(String partial) {
        // ========== 前置检查 ==========
        // null 或空字符串无法修复
        if (partial == null || partial.trim().isEmpty()) {
            return null;
        }

        String trimmed = partial.trim();

        // 只处理以 { 或 [ 开头的 JSON 结构
        // 如果以其他字符开头（如字符串、数字、null），不尝试修复
        if (trimmed.charAt(0) != '{' && trimmed.charAt(0) != '[') {
            return null;
        }

        // ========== 初始化状态变量 ==========
        StringBuilder result = new StringBuilder(trimmed);

        // 状态机追踪变量：
        boolean inString = false;  // 当前是否在字符串内部（双引号之间）
        boolean escaped = false;   // 前一个字符是否为转义符（\），用于正确处理 \" 等转义序列
        // 使用 StringBuilder 作为栈，追踪未关闭的括号类型
        // 遇到 { 时压入 }，遇到 [ 时压入 ]，遇到 } 或 ] 时弹出
        StringBuilder bracketStack = new StringBuilder();

        // ========== 遍历每个字符，追踪括号匹配状态 ==========
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);

            // ---- 处理 1：转义字符 ----
            // 如果前一个字符是 \，说明当前字符被转义，不参与任何解析逻辑
            if (escaped) {
                escaped = false;
                continue;
            }

            // ---- 处理 2：转义符标记 ----
            // 在字符串内部遇到 \ 标记转义状态，下一个字符将被跳过
            if (ch == '\\' && inString) {
                escaped = true;
                continue;
            }

            // ---- 处理 3：双引号切换字符串内/外状态 ----
            // 双引号是字符串的边界，遇到时切换 inString 状态
            if (ch == '"') {
                inString = !inString;
                continue;
            }

            // ---- 处理 4：字符串内部不追踪括号匹配 ----
            // 括号在字符串内部只是普通字符，不参与结构分析
            if (inString) {
                continue;
            }

            // ---- 处理 5：括号匹配追踪 ----
            // 遇到开括号时，将对应的闭括号入栈
            // 遇到闭括号时，从栈顶弹出（移除一个未匹配的闭括号需求）
            switch (ch) {
                case '{' -> bracketStack.append('}'); // 对象开始，压入 }
                case '[' -> bracketStack.append(']'); // 数组开始，压入 ]
                case '}', ']' -> {
                    // 闭括号：弹出对应的开括号（从栈顶移除一个匹配需求）
                    if (!bracketStack.isEmpty()) {
                        bracketStack.deleteCharAt(bracketStack.length() - 1);
                    }
                }
            }
        }

        // ========== 修复步骤 1：补全未关闭的字符串 ==========
        // 如果遍历结束时 inString 仍为 true，说明字符串被截断（如 "key": "value 缺少结尾引号）
        // 在末尾补一个双引号关闭字符串
        if (inString) {
            result.append('"');
        }

        // ========== 修复步骤 2：移除末尾的悬挂逗号和冒号 ==========
        // 流式传输中常见的截断场景：最后一个键值对之后的多余符号
        // 例如：{"key": "value",  → 移除末尾的逗号
        // 例如：{"key":            → 移除末尾的冒号
        String current = result.toString().trim();
        while (current.endsWith(",") || current.endsWith(":")) {
            current = current.substring(0, current.length() - 1).trim();
        }
        result = new StringBuilder(current);

        // ========== 修复步骤 3：按逆序补全所有未关闭的括号 ==========
        // 栈中剩余的元素就是需要补全的闭括号，按 LIFO 顺序追加到末尾
        // 例如：{"a": [1, 2   → 栈中剩余 "]" 和 "}" → 追加 "]}"
        for (int i = bracketStack.length() - 1; i >= 0; i--) {
            result.append(bracketStack.charAt(i));
        }

        return result.toString();
    }
}