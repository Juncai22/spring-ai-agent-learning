package com.pi.coding.session;

import com.pi.ai.core.types.Model;

/**
 * 循环切换模型的结果。
 *
 * <p>当用户通过 cycleModel() 切换到列表中的下一个模型时返回此结果。
 * 包含新模型信息、切换后的思考级别，以及可选的提示消息。
 *
 * @param model         切换后的新模型
 * @param thinkingLevel 切换后的思考级别
 * @param message       关于模型变更的可选提示消息（可为 null）
 */
public record ModelCycleResult(
        Model model,
        String thinkingLevel,
        String message
) {
}