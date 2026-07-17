package com.pi.agent.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pi.ai.core.types.UserContentBlock;

import java.util.List;

/**
 * 由 {@link com.pi.agent.config.AfterToolCallHook} 返回的结果。
 *
 * <p>每个非 {@code null} 字段会覆盖原始工具执行结果中对应的字段。
 * 保留为 {@code null} 的字段则保持原始值不变（字段级合并语义）。
 *
 * @param content 替换的内容块列表（文本/图片），或 {@code null} 保留原始内容
 * @param details 替换的详情载荷，或 {@code null} 保留原始详情
 * @param isError 替换的错误标志，或 {@code null} 保留原始错误标志
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AfterToolCallResult(
        List<UserContentBlock> content,
        Object details,
        Boolean isError
) {
}