package com.pi.coding.prompt;

import com.pi.coding.resource.ContextFile;
import com.pi.coding.resource.Skill;
import com.pi.coding.resource.Skills;

import java.time.LocalDate;
import java.util.*;

/**
 * 系统提示词（System Prompt）构建器 —— 组装 Agent 的系统提示词，
 * 包含工具列表、行为准则、技能描述和项目上下文。
 *
 * <p>这是 Agent 初始化流程中的核心组件，负责将各个配置片段组装为一个
 * 完整的系统提示词字符串，注入到 LLM 的上下文窗口中。系统提示词定义了
 * Agent 的身份、可用工具、行为准则和项目特定的上下文信息。</p>
 *
 * <h3>支持两种模式</h3>
 * <ol>
 *   <li><b>自定义提示词模式（Custom Prompt）</b>：
 *       完全使用用户提供的自定义提示词替换默认模板，仅在末尾附加上下文文件、
 *       技能描述和日期/工作目录信息。适用于需要完全控制提示词内容的场景。</li>
 *   <li><b>默认提示词模式（Default Prompt）</b>：
 *       使用内置模板，包含 Agent 身份声明、可用工具列表及其描述、
 *       基于工具的行为准则、额外的用户准则、项目上下文文件、技能描述
 *       以及当前日期和工作目录信息。适用于大多数场景。</li>
 * </ol>
 *
 * <h3>提示词结构（默认模式）</h3>
 * <pre>
 * [Agent 身份声明]
 * [可用工具列表]
 * [行为准则（基于工具自动生成 + 用户额外提供）]
 * [附加提示词（可选）]
 * [项目上下文文件]
 * [技能描述]
 * [当前日期]
 * [当前工作目录]
 * </pre>
 *
 * <p><b>验证需求：Requirements 24.1-24.8</b></p>
 */
public final class SystemPromptBuilder {

    /**
     * 内置工具描述映射 —— 为 Agent 核心工具提供默认的英文单行描述。
     * <p>
     * 这些描述会在提示词的"可用工具"部分展示给 LLM，帮助 LLM 理解
     * 每个工具的功能和适用场景。如果 {@link SystemPromptConfig#toolSnippets()}
     * 中提供了同名工具的覆盖描述，则优先使用覆盖描述。
     * </p>
     */
    private static final Map<String, String> TOOL_DESCRIPTIONS = Map.of(
            "read", "Read file contents",
            "bash", "Execute bash commands (ls, grep, find, etc.)",
            "edit", "Make surgical edits to files (find exact text and replace)",
            "write", "Create or overwrite files",
            "grep", "Search file contents for patterns (respects .gitignore)",
            "find", "Find files by glob pattern (respects .gitignore)",
            "ls", "List directory contents"
    );

    /**
     * 私有构造方法，防止实例化工具类。
     * SystemPromptBuilder 只提供静态方法，无需实例化。
     */
    private SystemPromptBuilder() {
        // Utility class
    }

    /**
     * 根据提供的配置构建系统提示词字符串。
     *
     * <p>这是构建器的入口方法，执行以下步骤：</p>
     * <ol>
     *   <li>规范化工作目录路径（将反斜杠替换为正斜杠）</li>
     *   <li>获取当前日期字符串</li>
     *   <li>处理可选的附加提示词文本</li>
     *   <li>根据是否设置了自定义提示词，选择自定义模式或默认模式构建</li>
     * </ol>
     *
     * @param config 系统提示词构建配置，包含所有输入参数，不能为 null
     * @return 组装完成后的系统提示词字符串
     */
    public static String buildSystemPrompt(SystemPromptConfig config) {
        // 规范化路径分隔符：Windows 的反斜杠替换为 Unix 风格的正斜杠
        String cwd = config.cwd().replace("\\", "/");
        // 获取当前日期，用于注入到提示词中使 LLM 感知当前时间
        String date = LocalDate.now().toString();
        // 处理附加提示词：如果存在，前加两个换行符作为分隔
        String appendSection = config.appendSystemPrompt() != null
                ? "\n\n" + config.appendSystemPrompt() : "";

        // 根据是否设置了自定义提示词，选择不同的构建模式
        if (config.customPrompt() != null) {
            return buildCustomPrompt(config, cwd, date, appendSection);
        }
        return buildDefaultPrompt(config, cwd, date, appendSection);
    }

    /**
     * 构建自定义模式下的系统提示词。
     *
     * <p>自定义模式以用户提供的提示词为起点，依次附加：</p>
     * <ol>
     *   <li>附加提示词文本（如果存在）</li>
     *   <li>项目上下文文件内容</li>
     *   <li>技能描述（仅在 read 工具可用时）</li>
     *   <li>当前日期和工作目录</li>
     * </ol>
     *
     * <p>不包含默认的 Agent 身份声明、工具列表和标准行为准则。</p>
     *
     * @param config        系统提示词配置
     * @param cwd           规范化后的工作目录路径
     * @param date          当前日期字符串
     * @param appendSection 附加提示词文本（可能为空字符串）
     * @return 组装后的自定义系统提示词
     */
    private static String buildCustomPrompt(SystemPromptConfig config, String cwd, String date, String appendSection) {
        StringBuilder prompt = new StringBuilder(config.customPrompt());

        if (!appendSection.isEmpty()) {
            prompt.append(appendSection);
        }

        // 追加项目上下文文件（如 AGENTS.md、CLAUD.md 等）
        appendContextFiles(prompt, config.contextFiles());

        // 技能描述仅在 read 工具可用时追加，因为技能通常通过读取文件来使用
        boolean hasRead = config.selectedTools().isEmpty() || config.selectedTools().contains("read");
        if (hasRead && !config.skills().isEmpty()) {
            prompt.append(Skills.formatSkillsForPrompt(config.skills()));
        }

        // 注入当前日期和工作目录，帮助 LLM 理解时间和空间上下文
        prompt.append("\nCurrent date: ").append(date);
        prompt.append("\nCurrent working directory: ").append(cwd);

        return prompt.toString();
    }

    /**
     * 构建默认模式下的系统提示词。
     *
     * <p>默认模式使用内置模板，包含以下部分：</p>
     * <ol>
     *   <li><b>Agent 身份声明</b>：声明 Agent 是 pi 编码 Agent 框架中的专家助手</li>
     *   <li><b>可用工具列表</b>：列出所有选中的工具及其描述</li>
     *   <li><b>行为准则</b>：根据可用工具自动生成使用准则 + 用户额外提供的准则</li>
     *   <li><b>附加提示词</b>（可选）：用户提供的额外提示文本</li>
     *   <li><b>项目上下文文件</b>：项目特定的配置和指导</li>
     *   <li><b>技能描述</b>：可用的 Agent 技能列表</li>
     *   <li><b>当前日期和工作目录</b></li>
     * </ol>
     *
     * @param config        系统提示词配置
     * @param cwd           规范化后的工作目录路径
     * @param date          当前日期字符串
     * @param appendSection 附加提示词文本（可能为空字符串）
     * @return 组装后的默认系统提示词
     */
    private static String buildDefaultPrompt(SystemPromptConfig config, String cwd, String date, String appendSection) {
        List<String> tools = config.selectedTools();

        // 构建工具列表部分
        String toolsList = buildToolsList(tools, config.toolSnippets());

        // 构建行为准则部分（基于可用工具自动生成 + 用户额外提供的准则）
        String guidelines = buildGuidelines(tools, config.promptGuidelines());

        StringBuilder prompt = new StringBuilder();
        // Agent 身份声明：明确告知 LLM 其角色和职责范围
        prompt.append("You are an expert coding assistant operating inside pi, a coding agent harness. ");
        prompt.append("You help users by reading files, executing commands, editing code, and writing new files.\n\n");
        // 可用工具列表
        prompt.append("Available tools:\n").append(toolsList).append("\n\n");
        prompt.append("In addition to the tools above, you may have access to other custom tools depending on the project.\n\n");
        // 行为准则
        prompt.append("Guidelines:\n").append(guidelines);

        // 附加提示词（在工具列表和准则之后）
        if (!appendSection.isEmpty()) {
            prompt.append(appendSection);
        }

        // 项目上下文文件
        appendContextFiles(prompt, config.contextFiles());

        // 技能描述：仅在 read 工具可用时追加
        // 原因：技能通常通过读取文件来触发，没有 read 工具时技能描述无意义
        if (tools.contains("read") && !config.skills().isEmpty()) {
            prompt.append(Skills.formatSkillsForPrompt(config.skills()));
        }

        // 注入当前日期和工作目录，使 LLM 感知时间和空间上下文
        prompt.append("\nCurrent date: ").append(date);
        prompt.append("\nCurrent working directory: ").append(cwd);

        return prompt.toString();
    }

    /**
     * 构建工具列表部分的文本。
     *
     * <p>遍历选中的工具列表，对每个工具查找其描述信息。
     * 优先使用 {@link SystemPromptConfig#toolSnippets()} 中的覆盖描述，
     * 如果未提供则使用内置的 {@link #TOOL_DESCRIPTIONS} 中的默认描述。
     * 如果某个工具既没有覆盖描述也没有默认描述，则跳过该工具不被列出。</p>
     *
     * @param tools        选中的工具名称列表
     * @param toolSnippets 工具名称到描述的映射（可为空，用于覆盖默认描述）
     * @return 格式化的工具列表字符串，每行以 "- toolName: description" 格式
     */
    private static String buildToolsList(List<String> tools, Map<String, String> toolSnippets) {
        List<String> lines = new ArrayList<>();
        for (String name : tools) {
            // 优先使用用户提供的工具描述片段，否则回退到内置描述
            String snippet = toolSnippets.getOrDefault(name, TOOL_DESCRIPTIONS.get(name));
            if (snippet != null) {
                lines.add("- " + name + ": " + snippet);
            }
        }
        return lines.isEmpty() ? "(none)" : String.join("\n", lines);
    }

    /**
     * 构建行为准则部分的文本。
     *
     * <p>行为准则根据 Agent 可用的工具集自动生成，确保 LLM 按照最佳实践使用工具。
     * 生成逻辑基于以下工具组合规则：</p>
     * <ul>
     *   <li><b>文件探索准则</b>：如果同时有 bash 和 grep/find/ls，建议优先使用专门工具</li>
     *   <li><b>编辑准则</b>：有 read 和 edit 时，要求先读后改</li>
     *   <li><b>精确修改准则</b>：edit 用于精确修改，必须精确匹配原文</li>
     *   <li><b>写入准则</b>：write 仅用于新文件或完全重写</li>
     *   <li><b>总结准则</b>：有编辑或写入能力时，要求直接输出文本而非用 bash 展示</li>
     *   <li><b>额外准则</b>：用户通过配置提供的额外行为准则</li>
     *   <li><b>通用准则</b>：始终包含"简洁回应"和"清晰显示文件路径"</li>
     * </ul>
     *
     * <p>使用 {@link LinkedHashSet} 去重，保持添加顺序的同时避免重复准则。</p>
     *
     * @param tools           选中的工具名称列表
     * @param extraGuidelines 用户额外提供的行为准则列表
     * @return 格式化的行为准则字符串，每行以 "- " 开头
     */
    private static String buildGuidelines(List<String> tools, List<String> extraGuidelines) {
        // 使用 LinkedHashSet 保持插入顺序并去重
        Set<String> seen = new LinkedHashSet<>();
        List<String> guidelines = new ArrayList<>();

        // 检查各工具是否可用
        boolean hasBash = tools.contains("bash");
        boolean hasEdit = tools.contains("edit");
        boolean hasWrite = tools.contains("write");
        boolean hasGrep = tools.contains("grep");
        boolean hasFind = tools.contains("find");
        boolean hasLs = tools.contains("ls");
        boolean hasRead = tools.contains("read");

        // 文件探索准则：根据可用工具组合生成不同的使用建议
        if (hasBash && !hasGrep && !hasFind && !hasLs) {
            // 只有 bash 没有专门的探索工具时，建议使用 bash 进行文件操作
            addGuideline(guidelines, seen, "Use bash for file operations like ls, rg, find");
        } else if (hasBash && (hasGrep || hasFind || hasLs)) {
            // 有专门的探索工具时，建议优先使用它们（更快、遵循 .gitignore）
            addGuideline(guidelines, seen,
                    "Prefer grep/find/ls tools over bash for file exploration (faster, respects .gitignore)");
        }

        // 编辑准则：先读后改，避免盲目修改
        if (hasRead && hasEdit) {
            addGuideline(guidelines, seen,
                    "Use read to examine files before editing. You must use this tool instead of cat or sed.");
        }

        // 精确修改准则：edit 要求精确匹配原文
        if (hasEdit) {
            addGuideline(guidelines, seen, "Use edit for precise changes (old text must match exactly)");
        }

        // 写入准则：write 仅用于新文件或完全重写
        if (hasWrite) {
            addGuideline(guidelines, seen, "Use write only for new files or complete rewrites");
        }

        // 总结准则：避免用 bash 展示已做的修改，直接输出文本更高效
        if (hasEdit || hasWrite) {
            addGuideline(guidelines, seen,
                    "When summarizing your actions, output plain text directly - do NOT use cat or bash to display what you did");
        }

        // 用户通过配置额外提供的准则
        for (String g : extraGuidelines) {
            String normalized = g.trim();
            if (!normalized.isEmpty()) {
                addGuideline(guidelines, seen, normalized);
            }
        }

        // 始终包含的通用准则
        addGuideline(guidelines, seen, "Be concise in your responses");
        addGuideline(guidelines, seen, "Show file paths clearly when working with files");

        // 将准则列表格式化为以 "- " 开头的多行文本
        return guidelines.stream()
                .map(g -> "- " + g)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    /**
     * 向准则列表中添加一条准则，自动去重。
     *
     * <p>通过 {@link Set#add} 的返回值判断是否已存在相同准则，避免重复添加。</p>
     *
     * @param guidelines 准则列表（输出参数）
     * @param seen       已添加准则的集合，用于去重
     * @param guideline  待添加的准则文本
     */
    private static void addGuideline(List<String> guidelines, Set<String> seen, String guideline) {
        if (seen.add(guideline)) {
            guidelines.add(guideline);
        }
    }

    /**
     * 向提示词末尾追加项目上下文文件部分。
     *
     * <p>上下文文件（如 AGENTS.md、CLAUDE.md、项目约定文档等）提供了
     * 项目特定的指令和约束，帮助 LLM 理解项目的编码规范、架构约定
     * 和特殊要求。</p>
     *
     * <p>格式：</p>
     * <pre>
     * # Project Context
     *
     * Project-specific instructions and guidelines:
     *
     * ## [文件路径]
     *
     * [文件内容]
     * </pre>
     *
     * @param prompt       提示词构建器（输出参数，内容会被追加）
     * @param contextFiles 上下文文件列表，如果为空则跳过
     */
    private static void appendContextFiles(StringBuilder prompt, List<ContextFile> contextFiles) {
        if (contextFiles.isEmpty()) return;

        prompt.append("\n\n# Project Context\n\n");
        prompt.append("Project-specific instructions and guidelines:\n\n");
        for (ContextFile cf : contextFiles) {
            // 使用文件路径作为二级标题，使 LLM 能够区分不同来源的上下文信息
            prompt.append("## ").append(cf.path()).append("\n\n");
            prompt.append(cf.content()).append("\n\n");
        }
    }
}
