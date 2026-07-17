package com.pi.coding.prompt;

import com.pi.coding.resource.ContextFile;
import com.pi.coding.resource.Skill;

import java.util.List;
import java.util.Map;

/**
 * 系统提示词（System Prompt）构建配置 —— 定义构建 Agent 系统提示词所需的所有参数。
 *
 * <p>该配置记录封装了构建系统提示词所需的各种输入，包括：
 * <ul>
 *   <li>工作目录和日期信息（自动注入到提示词中）</li>
 *   <li>预加载的技能（Skills）描述，让 LLM 了解可用能力</li>
 *   <li>项目上下文文件（如 AGENTS.md），提供项目特定的指导</li>
 *   <li>可选的自定义提示词，完全替换默认提示词模板</li>
 *   <li>可选的附加提示词文本，追加到默认提示词末尾</li>
 *   <li>选中的工具列表及其代码片段描述</li>
 *   <li>额外的行为准则提示</li>
 * </ul>
 * </p>
 *
 * <p><b>验证需求：Requirement 24.1</b></p>
 *
 * @param cwd                当前工作目录路径
 * @param skills             预加载的技能列表，每个技能包含名称和描述
 * @param contextFiles       预加载的上下文文件列表（如 AGENTS.md、CLAUD.md 等）
 * @param customPrompt       自定义系统提示词，设置后将完全替换默认提示词（可为 null）
 * @param appendSystemPrompt 要追加到系统提示词末尾的附加文本（可为 null）
 * @param selectedTools      要包含在提示词中的工具名称列表，默认包含 read/bash/edit/write
 * @param toolSnippets       工具名称到单行描述代码片段的映射（可选），用于覆盖默认工具描述
 * @param promptGuidelines   额外的行为准则提示点列表，会追加到默认准则之后
 */
public record SystemPromptConfig(
        String cwd,
        List<Skill> skills,
        List<ContextFile> contextFiles,
        String customPrompt,
        String appendSystemPrompt,
        List<String> selectedTools,
        Map<String, String> toolSnippets,
        List<String> promptGuidelines
) {
    /**
     * 紧凑构造器 —— 对空值字段提供合理的默认值。
     * <ul>
     *   <li>cwd 默认使用系统属性 "user.dir"</li>
     *   <li>skills、contextFiles、promptGuidelines 默认为空列表</li>
     *   <li>selectedTools 默认为 ["read", "bash", "edit", "write"]</li>
     *   <li>toolSnippets 默认为空映射</li>
     * </ul>
     */
    public SystemPromptConfig {
        if (cwd == null) cwd = System.getProperty("user.dir");
        if (skills == null) skills = List.of();
        if (contextFiles == null) contextFiles = List.of();
        if (selectedTools == null) selectedTools = List.of("read", "bash", "edit", "write");
        if (toolSnippets == null) toolSnippets = Map.of();
        if (promptGuidelines == null) promptGuidelines = List.of();
    }
}
