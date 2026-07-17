package com.pi.coding.resource;

/**
 * SkillsWatcher 配置。
 *
 * <p>用于配置 Skills 文件监听器的参数，包括监控目录、防抖延迟和变化回调。
 *
 * @param agentDir         Agent 配置目录（用户级 Skills 目录的父目录）
 * @param cwd              当前工作目录（项目级 Skills 目录的父目录）
 * @param debounceDelayMs  防抖延迟时间（毫秒）
 * @param onChangeCallback 文件变化时的回调
 */
public record SkillsWatcherConfig(
    String agentDir,
    String cwd,
    long debounceDelayMs,
    Runnable onChangeCallback
) {
    /**
     * 默认防抖延迟时间（毫秒）。
     */
    public static final long DEFAULT_DEBOUNCE_DELAY_MS = 500L;
    
    /**
     * 紧凑构造函数，用于参数校验。
     */
    public SkillsWatcherConfig {
        if (agentDir == null || agentDir.isEmpty()) {
            throw new IllegalArgumentException("agentDir cannot be null or empty");
        }
        if (cwd == null || cwd.isEmpty()) {
            throw new IllegalArgumentException("cwd cannot be null or empty");
        }
        if (debounceDelayMs <= 0) {
            throw new IllegalArgumentException("debounceDelayMs must be positive, got: " + debounceDelayMs);
        }
        if (onChangeCallback == null) {
            throw new IllegalArgumentException("onChangeCallback cannot be null");
        }
    }
    
    /**
     * 使用默认防抖延迟创建配置。
     *
     * @param agentDir         Agent 配置目录
     * @param cwd              当前工作目录
     * @param onChangeCallback 文件变化时的回调
     */
    public SkillsWatcherConfig(String agentDir, String cwd, Runnable onChangeCallback) {
        this(agentDir, cwd, DEFAULT_DEBOUNCE_DELAY_MS, onChangeCallback);
    }
    
    /**
     * 获取用户级 Skills 目录路径。
     *
     * @return 用户级 Skills 目录路径（agentDir/skills）
     */
    public String getUserSkillsDir() {
        return agentDir + "/skills";
    }
    
    /**
     * 获取项目级 Skills 目录路径。
     *
     * @return 项目级 Skills 目录路径（cwd/.kiro/skills）
     */
    public String getProjectSkillsDir() {
        return cwd + "/.kiro/skills";
    }
}
