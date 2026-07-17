package com.pi.coding.resource;

/**
 * 技能（Skill）记录，表示从 SKILL.md 文件中加载的一个技能。
 *
 * <p>技能是 Agent 系统的核心扩展机制，每个技能包含一个名称和描述，
 * 存储在 SKILL.md 文件中。当 Agent 需要执行特定任务时，会根据
 * 技能描述匹配并加载对应的技能文件。
 *
 * <p>技能发现规则：
 * <ul>
 *   <li>目录中包含 SKILL.md 文件，则视为一个技能根目录，不再递归</li>
 *   <li>如果目录不包含 SKILL.md，则扫描直接子目录递归查找</li>
 *   <li>兼容直接以 .md 文件作为技能（根目录级别）</li>
 * </ul>
 *
 * <p>技能来源标识：
 * <ul>
 *   <li><b>user</b> — 用户级技能，来自 {agentDir}/skills/</li>
 *   <li><b>project</b> — 项目级技能，来自 {cwd}/.kiro/skills/</li>
 *   <li><b>path</b> — 通过显式路径配置加载的技能</li>
 * </ul>
 *
 * @param name                   技能名称，必须与父目录名一致
 * @param description            技能描述，用于匹配任务
 * @param filePath               SKILL.md 文件的完整路径
 * @param baseDir                SKILL.md 文件的父目录
 * @param source                 来源标识（"user" / "project" / "path"）
 * @param disableModelInvocation 是否排除在模型提示词之外
 */
public record Skill(
    String name,
    String description,
    String filePath,
    String baseDir,
    String source,
    boolean disableModelInvocation
) {
    /**
     * 紧凑构造函数，校验所有必需参数。
     *
     * @throws IllegalArgumentException 如果 name、description、filePath、baseDir 或 source 为空
     */
    public Skill {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (description == null || description.isEmpty()) {
            throw new IllegalArgumentException("description 不能为空");
        }
        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("filePath 不能为空");
        }
        if (baseDir == null || baseDir.isEmpty()) {
            throw new IllegalArgumentException("baseDir 不能为空");
        }
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("source 不能为空");
        }
    }
}
