package com.pi.coding.extension;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 命令信息 —— 用于 UI 显示的命令信息。
 *
 * <p>包含命令的基本信息，包括名称、描述、来源和位置。
 * 用于在 UI 中展示可用命令列表，帮助用户了解每个命令的用途和来源。
 *
 * @param name        命令名称（不含前导斜杠）
 * @param description 命令描述
 * @param source      命令来源（"builtin" 表示内置命令，"extension" 表示扩展注册的命令）
 * @param location    来源位置（扩展命令的扩展路径）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandInfo(
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("source") String source,
    @JsonProperty("location") String location
) {

    /** 内置命令来源标识 */
    public static final String SOURCE_BUILTIN = "builtin";
    /** 扩展命令来源标识 */
    public static final String SOURCE_EXTENSION = "extension";
}
