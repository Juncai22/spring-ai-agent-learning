package com.pi.coding.util;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 解析 Markdown 文件 YAML 前置元数据（Frontmatter）的结果记录。
 * <p>
 * 使用 Java 14+ 的 {@link Record} 类型定义，提供不可变的数据载体，
 * 包含解析后的前置元数据 {@link Map} 和剥离元数据后的正文内容。
 * 同时提供便捷的类型安全取值方法，用于从元数据中按类型提取字段值。
 * <p>
 * 本记录支持通过 Jackson 反序列化（{@link JsonCreator} + {@link JsonProperty}），
 * 便于在 REST API 或 JSON 序列化/反序列化场景中使用。
 *
 * @param data    解析后的前置元数据，以键值对形式存储。键为字符串，值为任意类型对象。
 *                若无前置元数据，则为空 Map（{@link Map#of()}）。
 *                传入的 Map 会在构造时被防御性复制为不可变 Map，确保数据安全性。
 * @param content 剥离前置元数据后的正文内容。若无前置元数据，则为原始文本内容。
 *                传入 null 时会被安全地转换为空字符串。
 */
public record FrontmatterResult(
    Map<String, Object> data,
    String content
) {

    /**
     * 全参数构造器，使用 Jackson 注解支持 JSON 反序列化。
     * <p>
     * 构造时对参数进行防御性处理：
     * <ul>
     *   <li>data 为 null 时替换为空 Map（{@link Map#of()}），非 null 时创建不可变副本</li>
     *   <li>content 为 null 时替换为空字符串</li>
     * </ul>
     * 这种处理方式确保 FrontmatterResult 实例始终处于有效状态，
     * 调用方无需对 data 和 content 进行额外的 null 检查。
     *
     * @param data    前置元数据 Map，可为 null
     * @param content 正文内容，可为 null
     */
    @JsonCreator
    public FrontmatterResult(
        @JsonProperty("data") Map<String, Object> data,
        @JsonProperty("content") String content
    ) {
        // 防御性复制：防止外部修改传入的 Map 影响本记录的状态
        this.data = data != null ? Map.copyOf(data) : Map.of();
        // 防御性处理：确保 content 不为 null，避免后续调用出现 NullPointerException
        this.content = content != null ? content : "";
    }

    /**
     * 从前置元数据中获取指定键的字符串值。
     * <p>
     * 如果键对应的值不为 null，则调用其 {@link Object#toString()} 方法进行转换；
     * 如果键不存在或值为 null，则返回 null。
     * 适用于获取标题、作者、日期等文本类型的元数据字段。
     *
     * @param key 元数据字段的键名，不可为 null
     * @return 键对应的字符串值，如果键不存在或值为 null 则返回 null
     */
    public String getString(String key) {
        Object value = data.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 从前置元数据中获取指定键的布尔值。
     * <p>
     * 支持两种类型的值转换：
     * <ul>
     *   <li>如果值为 {@link Boolean} 类型，直接返回该布尔值</li>
     *   <li>如果值为 {@link String} 类型，使用 {@link Boolean#parseBoolean(String)} 解析，
     *      该方法仅当字符串为 "true"（不区分大小写）时返回 true，其余情况返回 false</li>
     *   <li>其他类型或键不存在时，返回 null</li>
     * </ul>
     * 适用于获取 draft、published、archived 等布尔类型的元数据字段。
     *
     * @param key 元数据字段的键名，不可为 null
     * @return 键对应的布尔值，如果键不存在或值无法转换为布尔类型则返回 null
     */
    public Boolean getBoolean(String key) {
        Object value = data.get(key);
        // 直接处理 Boolean 类型，避免不必要的字符串解析
        if (value instanceof Boolean b) {
            return b;
        }
        // 处理字符串类型的布尔值，使用 Java 标准解析规则
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        // 不支持的值的类型，返回 null 表示无法转换
        return null;
    }
}
