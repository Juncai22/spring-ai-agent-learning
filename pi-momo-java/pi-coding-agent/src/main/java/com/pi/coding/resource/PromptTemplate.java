package com.pi.coding.resource;

/**
 * Prompt 模板记录，表示从 Markdown 文件中加载的提示模板。
 *
 * <p>Prompt 模板是 Agent 系统的重要功能，允许用户通过
 * 斜杠命令（/）快速调用预定义的提示模板。模板文件存储在
 * prompts 目录中，文件名（不含 .md 扩展名）即为模板名称。
 *
 * <p>模板内容可以包含参数占位符，在调用时会被实际参数替换：
 * <ul>
 *   <li>$1, $2, ... — 位置参数</li>
 *   <li>$@ 或 $ARGUMENTS — 所有参数</li>
 *   <li>${@:N} — 从第 N 个参数开始的所有参数</li>
 *   <li>${@:N:L} — 从第 N 个参数开始的 L 个参数</li>
 * </ul>
 *
 * <p>模板来源标识：
 * <ul>
 *   <li><b>user</b> — 用户级模板，来自 {agentDir}/prompts/</li>
 *   <li><b>project</b> — 项目级模板，来自 {cwd}/.kiro/prompts/</li>
 *   <li><b>path</b> — 通过显式路径配置加载的模板</li>
 * </ul>
 *
 * @param name        模板名称（文件名不含 .md 扩展名）
 * @param description 模板描述，来自 frontmatter 或首行内容
 * @param content     模板内容（frontmatter 之后的部分）
 * @param source      来源标识（"user" / "project" / "path"）
 * @param filePath    模板文件的完整路径
 */
public record PromptTemplate(
    String name,
    String description,
    String content,
    String source,
    String filePath
) {
    /**
     * 紧凑构造函数，校验所有必需参数。
     *
     * @throws IllegalArgumentException 如果 name、source、filePath 为空，或 description、content 为 null
     */
    public PromptTemplate {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (description == null) {
            throw new IllegalArgumentException("description 不能为 null");
        }
        if (content == null) {
            throw new IllegalArgumentException("content 不能为 null");
        }
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("source 不能为空");
        }
        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("filePath 不能为空");
        }
    }
}
