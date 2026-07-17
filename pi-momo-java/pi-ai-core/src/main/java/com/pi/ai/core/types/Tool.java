package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具定义，包含名称、描述和 JSON Schema 参数描述。
 * 用于向 LLM 注册可调用的工具，LLM 会根据这些定义生成工具调用请求。
 *
 * <p>{@code parameters} 使用 Jackson {@link JsonNode} 表示 JSON Schema 对象，
 * 与 networknt/json-schema-validator 的输入格式一致。
 *
 * @param name        工具名称，LLM 通过此名称引用工具
 * @param description 工具描述，LLM 根据描述决定何时调用工具
 * @param parameters  JSON Schema 参数定义，描述工具接受的参数格式
 */
// 序列化时忽略值为 null 的字段，减少 JSON 体积
@JsonInclude(JsonInclude.Include.NON_NULL)
// 使用 Java record 定义不可变数据载体
// 注意：Tool 没有实现任何接口，是独立的类型定义
public record Tool(
    // 工具名称：LLM 通过此名称在 ToolCall 中引用该工具
    @JsonProperty("name") String name,
    // 工具描述：自然语言描述工具的功能，LLM 根据此描述决定何时调用
    @JsonProperty("description") String description,
    // 参数定义：JSON Schema 格式，描述工具接受的参数结构、类型和约束
    // 使用 JsonNode 而非特定 POJO，以支持任意复杂的 JSON Schema
    @JsonProperty("parameters") JsonNode parameters
) { }