package com.pi.coding.session;

import com.pi.agent.Agent;
import com.pi.coding.extension.ToolDefinition;
import com.pi.coding.model.CodingModelRegistry;
import com.pi.coding.resource.ResourceLoader;
import com.pi.coding.settings.SettingsManager;

import java.util.List;

/**
 * AgentSession 的配置记录，用于创建 AgentSession 实例。
 *
 * <p>所有必需组件通过构造函数的参数注入，并在规范构造方法中进行空值检查。
 * 提供合理的默认值：cwd 默认为当前工作目录，空列表默认为空列表。
 *
 * <p>验证需求：2.1
 *
 * @param agent               底层 Agent 实例（pi-agent-core），负责 LLM 通信
 * @param sessionManager      会话持久化管理器，管理会话树结构和文件 I/O
 * @param settingsManager     设置管理器，管理自动压缩、重试等设置
 * @param cwd                 当前工作目录，用于 Bash 执行和文件操作
 * @param scopedModels        可用于循环切换的模型列表
 * @param resourceLoader      资源加载器，用于加载 skills、prompts、上下文文件等
 * @param customTools         扩展定义的自定义工具定义列表
 * @param modelRegistry       模型注册表，用于查找模型和获取 API Key
 * @param initialActiveToolNames 初始激活的工具名称列表
 */
public record AgentSessionConfig(
        Agent agent,
        SessionManager sessionManager,
        SettingsManager settingsManager,
        String cwd,
        List<ScopedModel> scopedModels,
        ResourceLoader resourceLoader,
        List<ToolDefinition> customTools,
        CodingModelRegistry modelRegistry,
        List<String> initialActiveToolNames
) {
    /**
     * 规范构造方法，对必需参数进行空值检查，为可选参数提供默认值。
     *
     * @throws NullPointerException 如果 agent、sessionManager 或 settingsManager 为 null
     */
    public AgentSessionConfig {
        if (agent == null) throw new NullPointerException("agent");
        if (sessionManager == null) throw new NullPointerException("sessionManager");
        if (settingsManager == null) throw new NullPointerException("settingsManager");
        if (cwd == null) cwd = System.getProperty("user.dir");
        if (scopedModels == null) scopedModels = List.of();
        if (customTools == null) customTools = List.of();
        if (initialActiveToolNames == null) initialActiveToolNames = List.of();
    }
}