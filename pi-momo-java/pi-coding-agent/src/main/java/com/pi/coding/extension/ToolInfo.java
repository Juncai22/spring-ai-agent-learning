package com.pi.coding.extension;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具信息 —— 包含工具名称、描述和参数模式。
 *
 * <p>用于 UI 展示或调试目的，不包含工具的执行函数。
 * 通过 {@link ExtensionAPI#getAllTools()} 返回。
 *
 * @param name        工具名称
 * @param description 工具描述
 * @param parameters  参数模式（JSON Schema 格式）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolInfo(
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("parameters") JsonNode parameters
) { }
