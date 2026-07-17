package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base sealed interface for all content block types.
 * 所有内容块类型的密封基接口，定义了内容块的通用契约。
 *
 * <p>Uses Jackson polymorphic deserialization based on the {@code type} field
 * to dispatch to the correct concrete record type.
 * 使用 Jackson 多态反序列化，根据 {@code type} 字段的值分发到具体的内容块类型。
 */
// Step 1: 配置 Jackson 多态类型信息，使用 type 字段作为类型标识
// 作用：序列化时写入 "type" 字段，反序列化时根据 type 值选择具体实现类
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
// Step 2: 注册具体子类型及其对应的 type 值
// 注意：这里没有 visible = true，因此 type 字段会被 Jackson 消费掉
@JsonSubTypes({
    @JsonSubTypes.Type(value = TextContent.class, name = "text"),
    @JsonSubTypes.Type(value = ThinkingContent.class, name = "thinking"),
    @JsonSubTypes.Type(value = ImageContent.class, name = "image"),
    @JsonSubTypes.Type(value = ToolCall.class, name = "toolCall")
})
// 密封接口：内容块分为两大类——用户侧（UserContentBlock）和助手侧（AssistantContentBlock）
public sealed interface ContentBlock permits UserContentBlock, AssistantContentBlock {

    /**
     * The discriminator value identifying the content block type.
     * 内容块类型的标识值（如 "text"、"thinking"、"image"、"toolCall"）。
     *
     * @return 类型标识字符串
     */
    // 接口方法：子类必须实现，返回内容块的类型标识
    // 如 "text" 表示文本、"thinking" 表示思考、"image" 表示图片、"toolCall" 表示工具调用
    String type();
}