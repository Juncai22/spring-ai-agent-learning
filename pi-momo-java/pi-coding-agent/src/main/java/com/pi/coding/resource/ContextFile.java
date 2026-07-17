package com.pi.coding.resource;

/**
 * 上下文文件记录，表示从项目中加载的上下文文件（如 AGENTS.md / CLAUDE.md）。
 *
 * <p>上下文文件是 Agent 系统的重要配置机制，允许在项目目录中放置
 * AGENTS.md 或 CLAUDE.md 文件来提供项目级别的上下文信息。
 * 这些文件会从当前目录向上遍历目录树发现，并按照目录层级
 * 从上到下排列，供 Agent 构建系统提示词时使用。
 *
 * <p>文件发现优先级：AGENTS.md 优先于 CLAUDE.md。
 * 两个文件同时存在时只加载 AGENTS.md。
 *
 * @param path    上下文文件的完整路径
 * @param content 上下文文件的内容
 */
public record ContextFile(
    String path,
    String content
) {
    /**
     * 紧凑构造函数，校验参数。
     *
     * @throws IllegalArgumentException 如果 path 为空或 content 为 null
     */
    public ContextFile {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (content == null) {
            throw new IllegalArgumentException("content 不能为 null");
        }
    }
}
