package com.pi.coding.resource;

import java.util.List;

/**
 * Skills 加载选项，配置从哪些位置加载技能。
 *
 * <p>支持从默认目录（用户级和项目级）和显式指定的路径加载技能。
 *
 * @param cwd             当前工作目录，用于查找项目级技能目录（{cwd}/.kiro/skills/）
 * @param agentDir        Agent 配置目录，用于查找用户级技能目录（{agentDir}/skills/）
 * @param skillPaths      显式指定的额外技能路径列表
 * @param includeDefaults 是否包含默认目录（用户级和项目级）
 */
public record LoadSkillsOptions(
    String cwd,
    String agentDir,
    List<String> skillPaths,
    boolean includeDefaults
) {
    /**
     * 紧凑构造函数，进行参数校验。
     *
     * @throws IllegalArgumentException 如果 cwd 或 agentDir 为空，或 skillPaths 为 null
     */
    public LoadSkillsOptions {
        if (cwd == null || cwd.isEmpty()) {
            throw new IllegalArgumentException("cwd 不能为空");
        }
        if (agentDir == null || agentDir.isEmpty()) {
            throw new IllegalArgumentException("agentDir 不能为空");
        }
        if (skillPaths == null) {
            throw new IllegalArgumentException("skillPaths 不能为 null");
        }
    }

    /**
     * 使用默认值创建加载选项（包含默认目录，无额外路径）。
     *
     * @param cwd      当前工作目录
     * @param agentDir Agent 配置目录
     */
    public LoadSkillsOptions(String cwd, String agentDir) {
        this(cwd, agentDir, List.of(), true);
    }
}
