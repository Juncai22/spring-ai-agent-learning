package com.pi.coding.resource;

import com.pi.coding.settings.SettingsManager;

/**
 * DefaultResourceLoader 的配置记录。
 *
 * <p>封装资源加载器所需的全部配置参数，包括工作目录、Agent 配置目录和配置管理器。
 * 紧凑构造函数会校验所有参数，确保配置完整有效。
 *
 * @param cwd              当前工作目录（用于查找项目级资源，如 .kiro/ 目录）
 * @param agentDir         Agent 配置目录（用于查找用户级全局资源）
 * @param settingsManager  配置管理器（用于获取显式配置的路径）
 */
public record ResourceLoaderConfig(
    String cwd,
    String agentDir,
    SettingsManager settingsManager
) {
    /**
     * 紧凑构造函数，进行参数校验。
     *
     * @throws IllegalArgumentException 如果 cwd 或 agentDir 为空，或 settingsManager 为 null
     */
    public ResourceLoaderConfig {
        if (cwd == null || cwd.isEmpty()) {
            throw new IllegalArgumentException("cwd 不能为空");
        }
        if (agentDir == null || agentDir.isEmpty()) {
            throw new IllegalArgumentException("agentDir 不能为空");
        }
        if (settingsManager == null) {
            throw new IllegalArgumentException("settingsManager 不能为 null");
        }
    }
}
