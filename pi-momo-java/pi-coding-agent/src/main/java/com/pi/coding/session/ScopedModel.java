package com.pi.coding.session;

import com.pi.ai.core.types.Model;

/**
 * 作用域模型：用于循环切换的模型，附带可选的思考级别覆盖。
 *
 * <p>当用户通过 cycleModel() 切换模型时，Agent 会按 ScopedModel 列表顺序循环。
 * 每个 ScopedModel 可以指定一个思考级别，切换模型时会自动应用。
 * 如果思考级别为 null，则保持当前思考级别不变。
 *
 * @param model         模型实例
 * @param thinkingLevel 可选的思考级别覆盖（可为 null，表示不更改思考级别）
 */
public record ScopedModel(
        Model model,
        String thinkingLevel
) {
    /**
     * 创建不指定思考级别覆盖的作用域模型。
     *
     * @param model 模型实例
     */
    public ScopedModel(Model model) {
        this(model, null);
    }
}