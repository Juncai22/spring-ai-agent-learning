package com.pi.coding.session;

import com.pi.ai.core.types.ImageContent;

import java.util.List;

/**
 * prompt 方法的选项配置。
 *
 * <p>控制发送 prompt 时的行为，包括是否展开提示模板、
 * 包含的图片、流式行为模式等。
 *
 * @param expandPromptTemplates 是否展开提示模板（如 {{skill:xxx}} 语法）
 * @param images                可选的图片列表，用于多模态输入
 * @param streamingBehavior     "steer" 或 "followUp" 流式行为模式（可为 null）
 * @param source                来源标识符（可为 null），用于跟踪 prompt 来源
 */
public record PromptOptions(
        boolean expandPromptTemplates,
        List<ImageContent> images,
        String streamingBehavior,
        String source
) {
    /**
     * 创建默认选项（展开模板，无图片，无流式行为，无来源）。
     *
     * @return 默认选项
     */
    public static PromptOptions defaults() {
        return new PromptOptions(true, List.of(), null, null);
    }

    /**
     * 创建包含图片的选项。
     *
     * @param images 图片内容列表
     * @return 包含图片的选项
     */
    public static PromptOptions withImages(List<ImageContent> images) {
        return new PromptOptions(true, images, null, null);
    }
}