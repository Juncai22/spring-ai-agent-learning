package com.pi.coding.resource;

import java.util.List;

/**
 * Skills 加载结果。
 *
 * <p>封装加载的技能列表和加载过程中产生的诊断信息。
 * 诊断信息包括：
 * <ul>
 *   <li>warning — 路径不存在、文件读取失败、名称校验不通过等</li>
 *   <li>collision — 技能名称重复导致冲突</li>
 * </ul>
 *
 * @param skills      加载的技能列表（去重后）
 * @param diagnostics 加载过程中的诊断信息列表
 */
public record LoadSkillsResult(
    List<Skill> skills,
    List<ResourceDiagnostic> diagnostics
) {
    /**
     * 紧凑构造函数，校验参数。
     *
     * @throws IllegalArgumentException 如果 skills 或 diagnostics 为 null
     */
    public LoadSkillsResult {
        if (skills == null) {
            throw new IllegalArgumentException("skills 不能为 null");
        }
        if (diagnostics == null) {
            throw new IllegalArgumentException("diagnostics 不能为 null");
        }
    }
}
