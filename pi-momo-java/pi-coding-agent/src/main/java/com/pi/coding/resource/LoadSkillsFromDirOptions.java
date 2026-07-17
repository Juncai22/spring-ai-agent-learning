package com.pi.coding.resource;

/**
 * 从目录加载技能的选项。
 *
 * <p>指定要扫描的目录和该目录下技能的来源标识。
 *
 * @param dir    要扫描的目录路径
 * @param source 来源标识，用于标记该目录下加载的技能来源
 *               （"user" / "project" / "path"）
 */
public record LoadSkillsFromDirOptions(
    String dir,
    String source
) {
    /**
     * 紧凑构造函数，进行参数校验。
     *
     * @throws IllegalArgumentException 如果 dir 或 source 为空
     */
    public LoadSkillsFromDirOptions {
        if (dir == null || dir.isEmpty()) {
            throw new IllegalArgumentException("dir 不能为空");
        }
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("source 不能为空");
        }
    }
}
