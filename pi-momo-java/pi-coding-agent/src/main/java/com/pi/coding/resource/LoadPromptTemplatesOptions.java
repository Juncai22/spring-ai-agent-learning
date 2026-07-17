package com.pi.coding.resource;

import java.util.List;

/**
 * Prompt 模板加载选项。
 *
 * <p>配置从哪些位置加载 Prompt 模板，包括默认目录和显式路径。
 *
 * @param cwd             当前工作目录，用于查找项目级模板目录（{cwd}/.kiro/prompts/）
 * @param agentDir        Agent 配置目录，用于查找用户级模板目录（{agentDir}/prompts/）
 * @param promptPaths     显式指定的额外模板路径列表
 * @param includeDefaults 是否包含默认目录（用户级和项目级）
 */
public record LoadPromptTemplatesOptions(
    String cwd,
    String agentDir,
    List<String> promptPaths,
    boolean includeDefaults
) {
    /**
     * 紧凑构造函数，进行参数校验。
     *
     * @throws IllegalArgumentException 如果 cwd 或 agentDir 为空，或 promptPaths 为 null
     */
    public LoadPromptTemplatesOptions {
        if (cwd == null || cwd.isEmpty()) {
            throw new IllegalArgumentException("cwd 不能为空");
        }
        if (agentDir == null || agentDir.isEmpty()) {
            throw new IllegalArgumentException("agentDir 不能为空");
        }
        if (promptPaths == null) {
            throw new IllegalArgumentException("promptPaths 不能为 null");
        }
    }

    /**
     * 使用默认值创建加载选项（包含默认目录，无额外路径）。
     *
     * @param cwd      当前工作目录
     * @param agentDir Agent 配置目录
     */
    public LoadPromptTemplatesOptions(String cwd, String agentDir) {
        this(cwd, agentDir, List.of(), true);
    }
}
