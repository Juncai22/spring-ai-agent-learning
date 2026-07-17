package com.pi.coding.resource;

import java.util.List;

/**
 * 资源变化事件，封装资源重载后的结果快照。
 *
 * <p>当资源加载器完成资源重载后，会创建此事件并通知所有注册的
 * {@link ResourceChangeListener}。监听器可以根据事件中的结果
 * 执行相应的更新操作，例如刷新系统提示词或更新缓存。
 *
 * <p>事件包含重载后的 Skills 结果、Prompts 结果以及加载过程中的
 * 诊断信息。事件创建时会自动记录时间戳，方便追踪重载时间。
 *
 * <p>使用示例：
 * <pre>{@code
 * listener.onResourceChanged(new ResourceChangeEvent(
 *     skillsResult, promptsResult, diagnostics, System.currentTimeMillis()
 * ));
 * }</pre>
 *
 * @param skillsResult  Skills 加载结果，包含技能列表和诊断信息
 * @param promptsResult Prompt 模板加载结果，包含模板列表和诊断信息
 * @param diagnostics   诊断信息列表（警告、冲突等）
 * @param timestamp     事件创建时间戳（毫秒，自 1970-01-01）
 */
public record ResourceChangeEvent(
    LoadSkillsResult skillsResult,
    LoadPromptsResult promptsResult,
    List<ResourceDiagnostic> diagnostics,
    long timestamp
) {
    /**
     * 创建一个只包含 Skills 结果的事件。
     *
     * @param skillsResult Skills 加载结果
     * @return 资源变化事件
     */
    public static ResourceChangeEvent ofSkills(LoadSkillsResult skillsResult) {
        return new ResourceChangeEvent(
            skillsResult,
            null,
            skillsResult != null ? skillsResult.diagnostics() : List.of(),
            System.currentTimeMillis()
        );
    }

    /**
     * 创建一个包含完整结果的事件。
     *
     * @param skillsResult  Skills 加载结果
     * @param promptsResult Prompts 加载结果
     * @param diagnostics   诊断信息
     * @return 资源变化事件
     */
    public static ResourceChangeEvent of(
        LoadSkillsResult skillsResult,
        LoadPromptsResult promptsResult,
        List<ResourceDiagnostic> diagnostics
    ) {
        return new ResourceChangeEvent(
            skillsResult,
            promptsResult,
            diagnostics != null ? diagnostics : List.of(),
            System.currentTimeMillis()
        );
    }
}
