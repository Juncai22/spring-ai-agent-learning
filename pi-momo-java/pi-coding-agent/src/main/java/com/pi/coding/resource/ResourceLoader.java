package com.pi.coding.resource;

import com.pi.coding.extension.LoadExtensionsResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 资源加载器接口，负责加载 Agent 的所有资源。
 *
 * <p>资源加载器是 Agent 资源管理的核心接口，统一管理以下五类资源的加载与生命周期：
 * <ul>
 *   <li><b>Extensions（扩展）</b> — 通过扩展机制加载的额外资源路径</li>
 *   <li><b>Skills（技能）</b> — 从 SKILL.md 文件中发现的专用指令</li>
 *   <li><b>Prompts（提示模板）</b> — 从 prompts 目录加载的提示模板</li>
 *   <li><b>Context Files（上下文文件）</b> — 从目录向上遍历发现的 AGENTS.md / CLAUDE.md</li>
 *   <li><b>System Prompts（系统提示）</b> — SYSTEM.md 和 APPEND_SYSTEM.md 文件</li>
 * </ul>
 *
 * <p>资源加载支持以下加载来源（优先级从低到高）：
 * <ol>
 *   <li>全局 Agent 目录（agentDir/）</li>
 *   <li>项目本地目录（cwd/.kiro/）</li>
 *   <li>配置中显式指定的路径</li>
 *   <li>扩展模块提供的额外路径</li>
 * </ol>
 *
 * <p><b>热重载支持：</b>
 * 实现类可通过 {@link #addChangeListener(ResourceChangeListener)} 注册监听器，
 * 并通过 {@link #startWatching()} 启动文件系统监控，实现资源的热更新。
 * 当检测到文件变化时，资源会自动重新加载并通知所有注册的监听器。
 *
 * <p>使用示例：
 * <pre>{@code
 * ResourceLoader loader = new DefaultResourceLoader(config);
 * loader.addChangeListener(event -> {
 *     System.out.println("Skills: " + event.skillsResult().skills().size());
 *     System.out.println("Prompts: " + event.promptsResult().prompts().size());
 * });
 * loader.startWatching();
 * // ... 应用运行 ...
 * loader.dispose();
 * }</pre>
 */
public interface ResourceLoader {
    
    /**
     * 从所有配置的位置重新加载全部资源。
     *
     * <p>此方法会依次执行以下加载步骤：
     * <ol>
     *   <li>加载 Skills（技能）</li>
     *   <li>加载 Prompt 模板</li>
     *   <li>加载上下文文件（AGENTS.md / CLAUDE.md）</li>
     *   <li>加载系统提示（SYSTEM.md）</li>
     *   <li>加载附加系统提示（APPEND_SYSTEM.md）</li>
     * </ol>
     *
     * <p>如果加载过程中发生错误，会自动恢复至上一状态，保证资源加载的原子性。
     * 加载完成后会通知所有注册的 {@link ResourceChangeListener}。
     *
     * @return 异步操作结果，可以通过 {@link CompletableFuture#join()} 等待完成
     */
    CompletableFuture<Void> reload();
    
    /**
     * 获取已加载的扩展结果。
     *
     * <p>扩展结果由 {@link com.pi.coding.extension.ExtensionLoader} 外部加载后，
     * 通过 {@link DefaultResourceLoader#setExtensions(LoadExtensionsResult)} 注入。
     * 扩展可以贡献额外的资源路径（如 Skills、Prompts 路径）。
     *
     * @return 扩展加载结果，包含扩展列表和诊断信息
     */
    LoadExtensionsResult getExtensions();

    /**
     * 获取已加载的 Skills 及其诊断信息。
     *
     * <p>Skills 从以下位置发现（按优先级从低到高）：
     * <ul>
     *   <li>用户级目录：{agentDir}/skills/</li>
     *   <li>项目级目录：{cwd}/.kiro/skills/</li>
     *   <li>配置中显式指定的路径</li>
     *   <li>扩展模块贡献的路径</li>
     * </ul>
     *
     * @return Skills 加载结果，包含技能列表和诊断信息
     */
    LoadSkillsResult getSkills();

    /**
     * 获取已加载的 Prompt 模板。
     *
     * <p>Prompt 模板从以下位置加载：
     * <ul>
     *   <li>用户级目录：{agentDir}/prompts/</li>
     *   <li>项目级目录：{cwd}/.kiro/prompts/</li>
     *   <li>配置中显式指定的路径</li>
     *   <li>扩展模块贡献的路径</li>
     * </ul>
     *
     * @return Prompt 模板加载结果，包含模板列表和诊断信息
     */
    LoadPromptsResult getPrompts();

    /**
     * 获取 AGENTS.md / CLAUDE.md 上下文文件列表。
     *
     * <p>上下文文件从当前目录向上遍历目录树发现，直到文件系统根目录。
     * 全局上下文文件（{agentDir}/AGENTS.md）也会被加载。
     * 这些文件按目录层级从上到下排列，上层目录的优先级更高。
     *
     * @return 上下文文件列表，按从根到当前目录的顺序排列
     */
    List<ContextFile> getAgentsFiles();

    /**
     * 获取自定义系统提示（SYSTEM.md）。
     *
     * <p>系统提示的查找顺序（优先级从高到低）：
     * <ol>
     *   <li>项目级目录：{cwd}/.kiro/SYSTEM.md</li>
     *   <li>全局目录：{agentDir}/SYSTEM.md</li>
     * </ol>
     *
     * @return 系统提示内容，如果未找到则返回 null
     */
    String getSystemPrompt();

    /**
     * 获取附加系统提示行（APPEND_SYSTEM.md）。
     *
     * <p>附加系统提示会在系统提示之后追加到 Agent 的系统消息中。
     * 查找顺序同 {@link #getSystemPrompt()}。
     *
     * @return 附加系统提示行的列表，如果不存在则返回空列表
     */
    List<String> getAppendSystemPrompt();

    /**
     * 获取资源加载过程中的诊断信息。
     *
     * <p>诊断信息包括：
     * <ul>
     *   <li>warning（警告）— 资源路径不存在、文件读取失败等</li>
     *   <li>collision（冲突）— 资源名称重复导致覆盖</li>
     * </ul>
     *
     * @return 诊断信息列表，可能为空
     */
    List<ResourceDiagnostic> getDiagnostics();

    /**
     * 扩展资源路径，从扩展模块贡献的额外路径。
     *
     * <p>此方法供 {@link com.pi.coding.extension.ExtensionLoader} 调用，
     * 将扩展模块贡献的资源路径合并到现有路径中。
     * 合并时会自动去重。
     *
     * @param paths 扩展模块贡献的资源路径，包含扩展、Skills、Prompts 路径
     */
    void extendResources(ResourceExtensionPaths paths);
    
    // ==================== 热重载支持 ====================

    /**
     * 添加资源变化监听器。
     *
     * <p>当资源重载完成后，所有注册的监听器会收到 {@link ResourceChangeEvent} 通知。
     * 监听器可以在此回调中执行相应的更新操作，如：
     * <ul>
     *   <li>更新系统提示词以包含新加载的 Skills</li>
     *   <li>刷新缓存中的 Prompt 模板</li>
     *   <li>记录资源变化日志</li>
     * </ul>
     *
     * <p>注意：监听器的回调方法在重载线程中执行，实现时需保证线程安全。
     *
     * @param listener 要添加的监听器
     */
    default void addChangeListener(ResourceChangeListener listener) {
        // Default no-op implementation for backward compatibility
    }

    /**
     * 移除之前添加的资源变化监听器。
     *
     * @param listener 要移除的监听器
     */
    default void removeChangeListener(ResourceChangeListener listener) {
        // Default no-op implementation for backward compatibility
    }

    /**
     * 启动文件监控，监听资源目录的文件变化。
     *
     * <p>启动后，当资源目录中的文件发生创建、修改或删除时，
     * 资源会自动重新加载，并通过 {@link #addChangeListener(ResourceChangeListener)}
     * 注册的监听器通知调用方。
     *
     * <p>监控的目录包括：
     * <ul>
     *   <li>用户级 Skills 目录：{agentDir}/skills</li>
     *   <li>项目级 Skills 目录：{cwd}/.kiro/skills</li>
     * </ul>
     *
     * <p>文件变化会通过防抖机制合并，避免频繁触发重载。
     * 默认防抖延迟为 500ms。
     */
    default void startWatching() {
        // Default no-op implementation for backward compatibility
    }

    /**
     * 停止文件监控。
     *
     * <p>停止后，资源目录的文件变化不再触发自动重载。
     * 会取消所有待执行的防抖任务。
     */
    default void stopWatching() {
        // Default no-op implementation for backward compatibility
    }

    /**
     * 释放所有资源并停止文件监控。
     *
     * <p>此方法应在 ResourceLoader 不再需要时调用，以确保：
     * <ul>
     *   <li>停止文件监控线程</li>
     *   <li>关闭 WatchService</li>
     *   <li>清空所有注册的监听器</li>
     * </ul>
     *
     * <p>调用此方法后，ResourceLoader 实例不应再被使用。
     */
    default void dispose() {
        stopWatching();
    }
}
