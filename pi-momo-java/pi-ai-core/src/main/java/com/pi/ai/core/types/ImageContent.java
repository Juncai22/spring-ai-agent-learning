package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Image content block. Appears in user messages and tool result messages.
 * 图片内容块。出现在用户消息和工具结果消息中，用于传递视觉信息。
 *
 * @param type     always {@code "image"} / 固定为 {@code "image"}
 * @param data     Base64-encoded image data / Base64 编码的图片数据
 * @param mimeType the image MIME type (e.g. {@code "image/jpeg"}, {@code "image/png"}) / 图片 MIME 类型（如 "image/jpeg"、"image/png"）
 */
// 图片内容块：仅实现 UserContentBlock 接口
// 原因：图片只能出现在用户消息或工具结果中，助手消息不会包含图片内容块
public record ImageContent(
    // 类型标识，固定为 "image"
    @JsonProperty("type") String type,
    // Base64 编码的图片二进制数据
    @JsonProperty("data") String data,
    // 图片 MIME 类型，用于告知 LLM 如何解码图片数据
    @JsonProperty("mimeType") String mimeType
) implements UserContentBlock {

    /**
     * Convenience constructor that sets type automatically.
     * 便捷构造方法，自动设置 type 为 "image"。
     *
     * @param data     Base64 编码的图片数据
     * @param mimeType 图片 MIME 类型
     */
    // Step 1: 便捷构造方法，自动设置 type 为 "image"
    // 原因：调用方无需关心 type 字段，框架自动填充
    public ImageContent(String data, String mimeType) {
        // Step 2: 委托给主构造器，type 固定为 "image"
        this("image", data, mimeType);
    }
}