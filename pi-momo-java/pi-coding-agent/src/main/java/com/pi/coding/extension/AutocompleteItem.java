package com.pi.coding.extension;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 自动补全项 —— 用于命令参数的自动补全功能。
 *
 * <p>当用户输入命令参数时，系统可以根据此自动补全项提供候选列表。
 * 每个自动补全项包含一个值（实际输入内容）、一个标签（显示文本）和描述。
 *
 * @param value       补全值（实际会输入到命令行中的内容）
 * @param label       显示标签（可为 null，默认使用 value）
 * @param description 补全项的描述（可为 null）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AutocompleteItem(
    @JsonProperty("value") String value,
    @JsonProperty("label") String label,
    @JsonProperty("description") String description
) {

    /**
     * 创建一个只有值的自动补全项。
     *
     * @param value 补全值
     */
    public AutocompleteItem(String value) {
        this(value, null, null);
    }

    /**
     * 创建包含值和标签的自动补全项。
     *
     * @param value 补全值
     * @param label 显示标签
     */
    public AutocompleteItem(String value, String label) {
        this(value, label, null);
    }
}
