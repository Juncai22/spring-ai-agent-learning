package com.pi.ai.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * JSON Schema 字符串枚举辅助工具。
 *
 * <p>某些 AI Provider（如 Google API）的 Function Calling 实现不支持 JSON Schema 的
 * {@code anyOf} 或 {@code const} 关键字。对于需要枚举值的场景，这些 Provider 要求使用
 * 最基础的 {@code {"type": "string", "enum": [...]}} 格式。
 *
 * <p>此工具提供简洁的 API 生成兼容的字符串枚举 JSON Schema：
 * <pre>{@code
 * {
 *   "type": "string",
 *   "enum": ["option1", "option2", "option3"],
 *   "description": "可选描述"
 * }
 * }</pre>
 *
 * <p>对应 TypeScript 中的 {@code utils/typebox-helpers.ts} 的 StringEnum 函数。
 */
public final class StringEnumHelper {

    private StringEnumHelper() {
        // 工具类，禁止实例化
    }

    /**
     * 生成字符串枚举 JSON Schema 节点。
     *
     * <p>生成的 Schema 结构：
     * <ul>
     *   <li>type: "string" — 固定为字符串类型</li>
     *   <li>enum: [...] — 枚举值列表，顺序与输入一致</li>
     *   <li>description: "..." — 可选的描述信息（仅在 description 非 null 时添加）</li>
     * </ul>
     *
     * @param values      枚举值列表，枚举值在此列表中出现的顺序就是 Schema 中的顺序
     * @param description 对枚举的描述，用于帮助 LLM 理解枚举值的含义（可为 null）
     * @return 包含 type、enum 和可选的 description 字段的 JSON Schema 节点
     */
    public static JsonNode stringEnum(List<String> values, String description) {
        // ========== 步骤 1：创建根对象节点 ==========
        // 使用全局 ObjectMapper 创建 ObjectNode（对应 JSON 对象 {}）
        ObjectNode schema = PiAiJson.MAPPER.createObjectNode();

        // ========== 步骤 2：设置 type 字段 ==========
        // 固定为 "string" 类型，兼容不支持 anyOf/const 的 Provider
        schema.put("type", "string");

        // ========== 步骤 3：创建 enum 数组并逐个添加枚举值 ==========
        // putArray 创建 ArrayNode，然后逐个 add 枚举值
        // 顺序与输入列表一致，LLM 通常按顺序选择
        ArrayNode enumArray = schema.putArray("enum");
        for (String value : values) {
            enumArray.add(value);
        }

        // ========== 步骤 4：可选添加 description 字段 ==========
        // 描述信息帮助 LLM 理解枚举值的业务含义，提高选择准确性
        if (description != null) {
            schema.put("description", description);
        }

        return schema;
    }

    /**
     * 生成字符串枚举 JSON Schema 节点（无描述信息）。
     *
     * <p>重载方法，适用于不需要描述信息的简单枚举场景。
     *
     * @param values 枚举值列表
     * @return 包含 type 和 enum 字段的 JSON Schema 节点
     */
    public static JsonNode stringEnum(List<String> values) {
        // 委托给完整版本，description 传 null
        return stringEnum(values, null);
    }
}