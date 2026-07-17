package com.pi.coding.resource;

import java.util.List;

/**
 * Prompt 模板加载结果。
 *
 * <p>封装加载的 Prompt 模板列表和加载过程中产生的诊断信息。
 * 诊断信息包括模板名称冲突等警告。
 *
 * @param prompts     加载的 Prompt 模板列表（去重后）
 * @param diagnostics 加载过程中的诊断信息列表
 */
public record LoadPromptsResult(
    List<PromptTemplate> prompts,
    List<ResourceDiagnostic> diagnostics
) {
    /**
     * 紧凑构造函数，校验参数。
     *
     * @throws IllegalArgumentException 如果 prompts 或 diagnostics 为 null
     */
    public LoadPromptsResult {
        if (prompts == null) {
            throw new IllegalArgumentException("prompts 不能为 null");
        }
        if (diagnostics == null) {
            throw new IllegalArgumentException("diagnostics 不能为 null");
        }
    }
}
