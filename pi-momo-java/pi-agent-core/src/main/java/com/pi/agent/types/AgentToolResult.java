package com.pi.agent.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pi.ai.core.types.UserContentBlock;

import java.util.List;

/**
 * {@link AgentTool} 执行后返回的结果。
 *
 * <p>{@code content} 包含工具生成的文本/图片内容块，
 * {@code details} 携带可选的工具特定结构化数据载荷。
 *
 * @param content 工具返回的内容块列表（文本/图片）
 * @param details 工具特定的结构化数据
 * @param <T>     details 载荷的类型参数
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentToolResult<T>(
        List<UserContentBlock> content,
        T details
) {
}