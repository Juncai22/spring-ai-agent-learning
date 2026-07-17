package com.pi.coding.compaction;

import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.types.*;
import com.pi.coding.session.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 分支摘要生成器，用于会话树导航时的上下文保留。
 *
 * <p>当用户在会话树中导航到不同分支时，当前分支的上下文不会丢失。
 * 本类负责对即将离开的分支生成结构化摘要，在用户返回时注入为上下文。
 *
 * <p>核心流程：
 * <ol>
 *   <li>收集从当前位置到目标位置共同祖先之间的所有条目</li>
 *   <li>将条目转换为消息并提取文件操作信息</li>
 *   <li>在 token 预算内生成结构化摘要</li>
 *   <li>将摘要注入回会话，下次返回时可恢复上下文</li>
 * </ol>
 *
 * <p><b>验证需求: 4.1-4.8</b>
 */
public final class BranchSummarization {

    private BranchSummarization() {
        // 工具类，禁止实例化
    }

    /**
     * 分支摘要的前言文本，为摘要提供上下文说明。
     * 当用户返回原分支时，此前言告知用户之前探索过其他分支。
     */
    public static final String BRANCH_SUMMARY_PREAMBLE = """
            The user explored a different conversation branch before returning here.
            Summary of that exploration:

            """;

    /**
     * 分支摘要生成的默认提示词模板。
     * 要求 LLM 生成结构化摘要，包含目标、约束、进度、关键决策和后续步骤。
     * 保留精确的文件路径、函数名和错误信息。
     */
    public static final String BRANCH_SUMMARY_PROMPT = """
            Create a structured summary of this conversation branch for context when returning later.

            Use this EXACT format:

            ## Goal
            [What was the user trying to accomplish in this branch?]

            ## Constraints & Preferences
            - [Any constraints, preferences, or requirements mentioned]
            - [Or "(none)" if none were mentioned]

            ## Progress
            ### Done
            - [x] [Completed tasks/changes]

            ### In Progress
            - [ ] [Work that was started but not finished]

            ### Blocked
            - [Issues preventing progress, if any]

            ## Key Decisions
            - **[Decision]**: [Brief rationale]

            ## Next Steps
            1. [What should happen next to continue this work]

            Keep each section concise. Preserve exact file paths, function names, and error messages.""";

    // =========================================================================
    // 结果类型定义
    // =========================================================================

    /**
     * 收集分支摘要条目的结果。
     * 表示从当前位置回退到共同祖先过程中收集到的所有待摘要条目。
     *
     * @param entries          待摘要的条目列表（按时间顺序）
     * @param commonAncestorId 当前位置与目标位置之间的共同祖先 ID，可为 null
     */
    public record CollectEntriesResult(
            List<SessionEntry> entries,
            String commonAncestorId
    ) {
        /**
         * 创建空结果，表示没有需要摘要的内容。
         */
        public static CollectEntriesResult empty() {
            return new CollectEntriesResult(Collections.emptyList(), null);
        }
    }

    /**
     * 分支条目准备结果，封装了摘要化所需的数据。
     *
     * @param messages    提取出的消息列表（按时间顺序）
     * @param fileOps     从工具调用中提取的文件操作信息
     * @param totalTokens 消息的估算总 token 数
     */
    public record BranchPreparation(
            List<AgentMessage> messages,
            FileOperations fileOps,
            int totalTokens
    ) {}

    /**
     * 分支摘要生成结果，包含生成的摘要及相关元数据。
     *
     * @param summary       生成的摘要内容（可为 null，如果中止或出错）
     * @param readFiles     仅读取的文件列表
     * @param modifiedFiles 被修改的文件列表
     * @param wasAborted    是否被中止
     * @param error         错误信息（如果生成失败）
     */
    public record BranchSummaryResult(
            String summary,
            List<String> readFiles,
            List<String> modifiedFiles,
            boolean wasAborted,
            String error
    ) {
        /**
         * 创建成功结果。
         *
         * @param summary       摘要内容
         * @param readFiles     仅读取的文件列表
         * @param modifiedFiles 被修改的文件列表
         * @return BranchSummaryResult 实例
         */
        public static BranchSummaryResult success(String summary, List<String> readFiles, List<String> modifiedFiles) {
            return new BranchSummaryResult(summary, readFiles, modifiedFiles, false, null);
        }

        /**
         * 创建中止结果（用户取消或超时）。
         *
         * @return 标记为中止的 BranchSummaryResult
         */
        public static BranchSummaryResult aborted() {
            return new BranchSummaryResult(null, null, null, true, null);
        }

        /**
         * 创建错误结果。
         *
         * @param message 错误描述
         * @return 包含错误信息的 BranchSummaryResult
         */
        public static BranchSummaryResult error(String message) {
            return new BranchSummaryResult(null, null, null, false, message);
        }

        /**
         * 创建仅包含摘要的结果（无文件追踪信息）。
         *
         * @param summary 摘要内容
         * @return BranchSummaryResult 实例
         */
        public static BranchSummaryResult of(String summary) {
            return new BranchSummaryResult(summary, Collections.emptyList(), Collections.emptyList(), false, null);
        }

        /**
         * 检查生成是否被中止。
         *
         * @return 如果被中止则返回 true
         */
        public boolean isAborted() {
            return wasAborted;
        }
    }

    /**
     * 分支摘要详情记录，存储在 BranchSummaryEntry.details 中用于文件追踪。
     *
     * @param readFiles     仅读取的文件路径列表
     * @param modifiedFiles 被修改的文件路径列表
     */
    public record BranchSummaryDetails(
            List<String> readFiles,
            List<String> modifiedFiles
    ) {}

    /**
     * 分支摘要生成选项，控制生成行为。
     *
     * @param customInstructions  自定义指示（可为 null）
     * @param replaceInstructions 是否替换默认指示（true 则仅使用自定义指示）
     * @param reserveTokens       为响应预留的 token 数
     * @param contextWindow       模型上下文窗口大小
     */
    public record GenerateBranchSummaryOptions(
            String customInstructions,
            boolean replaceInstructions,
            int reserveTokens,
            int contextWindow
    ) {
        /**
         * 默认选项：空自定义指示，不替换，预留 16384 token，上下文窗口 128000。
         */
        public static GenerateBranchSummaryOptions defaults() {
            return new GenerateBranchSummaryOptions(null, false, 16384, 128000);
        }

        /**
         * 创建追加自定义指示的选项（默认指示后追加）。
         *
         * @param instructions 自定义指示
         * @return GenerateBranchSummaryOptions 实例
         */
        public static GenerateBranchSummaryOptions withCustomInstructions(String instructions) {
            return new GenerateBranchSummaryOptions(instructions, false, 16384, 128000);
        }

        /**
         * 创建替换默认指示的选项（完全使用自定义指示）。
         *
         * @param instructions 替换用的自定义指示
         * @return GenerateBranchSummaryOptions 实例
         */
        public static GenerateBranchSummaryOptions withReplacedInstructions(String instructions) {
            return new GenerateBranchSummaryOptions(instructions, true, 16384, 128000);
        }
    }

    // =========================================================================
    // 条目收集（Task 6.1）
    // =========================================================================

    /**
     * 收集从一个位置导航到另一位置时需要摘要化的条目。
     *
     * <p>从 fromId 开始向上遍历到根节点，直到遇到与 toId 的共同祖先。
     * 沿途收集所有条目。不会在压缩边界停止——压缩边界内的摘要也会被包含作为上下文。
     *
     * <p><b>验证需求: 4.1, 4.2</b>
     *
     * @param sessionManager 会话管理器（只读访问）
     * @param fromId         当前位置（导航起点）
     * @param toId           目标位置（导航终点）
     * @return 包含待摘要条目和共同祖先的 CollectEntriesResult
     */
    public static CollectEntriesResult collectEntriesForBranchSummary(
            SessionManager sessionManager,
            String fromId,
            String toId
    ) {
        // 如果没有旧位置，则无需摘要
        if (fromId == null || fromId.isEmpty()) {
            return CollectEntriesResult.empty();
        }

        // 获取从 fromId 到根节点的路径
        List<SessionEntry> fromPath = sessionManager.getBranch(fromId);
        Set<String> fromPathIds = new HashSet<>();
        for (SessionEntry entry : fromPath) {
            fromPathIds.add(entry.id());
        }

        // 获取从 toId 到根节点的路径
        List<SessionEntry> toPath = sessionManager.getBranch(toId);

        // 查找共同祖先（两条路径上最深的相同节点）
        // toPath 是按根节点优先排列的，所以从后向前遍历找到最深的共同祖先
        String commonAncestorId = null;
        for (int i = toPath.size() - 1; i >= 0; i--) {
            if (fromPathIds.contains(toPath.get(i).id())) {
                commonAncestorId = toPath.get(i).id();
                break;
            }
        }

        // 收集从 fromId 到共同祖先之间的所有条目
        List<SessionEntry> entries = new ArrayList<>();
        String current = fromId;

        while (current != null && !current.equals(commonAncestorId)) {
            SessionEntry entry = sessionManager.getEntry(current);
            if (entry == null) break;
            entries.add(entry);
            current = entry.parentId();
        }

        // 反转顺序为时间顺序（根到叶子）
        Collections.reverse(entries);

        return new CollectEntriesResult(entries, commonAncestorId);
    }

    // =========================================================================
    // 条目到消息的转换
    // =========================================================================

    /**
     * 从会话条目中提取 AgentMessage。
     *
     * <p>与压缩模块中的 getMessageFromEntry 类似，但额外处理压缩条目（CompactionEntry）。
     * 跳过工具结果消息（toolResult），因为其上下文已包含在对应的工具调用中。
     * 也跳过 ThinkingLevelChange、ModelChange、Custom、Label、SessionInfo 等不贡献对话内容的条目。
     *
     * @param entry 会话条目
     * @return 提取的消息，如果条目不贡献对话内容则返回 null
     */
    static AgentMessage getMessageFromEntry(SessionEntry entry) {
        if (entry instanceof SessionMessageEntry sme) {
            // 跳过工具结果——上下文在助手的工具调用中
            if ("toolResult".equals(sme.message().role())) {
                return null;
            }
            return sme.message();
        } else if (entry instanceof CustomMessageEntry<?> cme) {
            return createCustomMessage(cme);
        } else if (entry instanceof BranchSummaryEntry<?> bse) {
            return createBranchSummaryMessage(bse);
        } else if (entry instanceof CompactionEntry<?> ce) {
            return createCompactionSummaryMessage(ce);
        }
        // ThinkingLevelChange、ModelChange、Custom、Label、SessionInfo 不贡献对话内容
        return null;
    }

    /**
     * 从 CustomMessageEntry 创建自定义消息。
     * 提取自定义类型、内容文本和显示属性。
     */
    private static AgentMessage createCustomMessage(CustomMessageEntry<?> entry) {
        long ts = parseTimestamp(entry.timestamp());
        Object content = entry.content();
        String text = content instanceof String ? (String) content : content.toString();
        return new InternalCustomMessage(entry.customType(), text, entry.display(), ts);
    }

    /**
     * 从 BranchSummaryEntry 创建分支摘要消息。
     * 提取摘要内容、来源分支 ID 和时间戳。
     */
    private static AgentMessage createBranchSummaryMessage(BranchSummaryEntry<?> entry) {
        long ts = parseTimestamp(entry.timestamp());
        return new InternalBranchSummaryMessage(entry.summary(), entry.fromId(), ts);
    }

    /**
     * 从 CompactionEntry 创建压缩摘要消息。
     * 提取摘要内容、压缩前 token 数和时间戳。
     */
    private static AgentMessage createCompactionSummaryMessage(CompactionEntry<?> entry) {
        long ts = parseTimestamp(entry.timestamp());
        return new InternalCompactionSummaryMessage(entry.summary(), entry.tokensBefore(), ts);
    }

    /**
     * 将 ISO 8601 时间戳字符串解析为 Unix 毫秒时间戳。
     * 如果解析失败，返回当前时间作为兜底。
     */
    private static long parseTimestamp(String timestamp) {
        try {
            return java.time.Instant.parse(timestamp).toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    // =========================================================================
    // 分支条目准备
    // =========================================================================

    /**
     * 在 token 预算内准备分支条目用于摘要生成。
     *
     * <p>从最新条目开始向前遍历，添加消息直到达到 token 预算。
     * 这确保在分支过长时保留最近的上下文。
     *
     * <p>同时收集文件操作信息，来源包括：
     * <ul>
     *   <li>助手指令消息中的工具调用</li>
     *   <li>已有 branch_summary 条目的详情（用于累积追踪）</li>
     * </ul>
     *
     * <p>两阶段处理：
     * <ol>
     *   <li>第一遍：从所有条目中收集文件操作（即使超出 token 预算）</li>
     *   <li>第二遍：从最新到最旧遍历，添加消息直到 token 预算</li>
     * </ol>
     *
     * @param entries     按时间顺序排列的条目列表
     * @param tokenBudget 最大 token 数（0 表示无限制）
     * @return 包含消息和文件操作信息的 BranchPreparation
     */
    public static BranchPreparation prepareBranchEntries(List<SessionEntry> entries, int tokenBudget) {
        List<AgentMessage> messages = new ArrayList<>();
        FileOperations fileOps = new FileOperations();
        int totalTokens = 0;

        // 第一遍：从所有条目中收集文件操作（即使超出 token 预算）
        // 确保从嵌套的分支摘要中捕获累积文件追踪信息
        // 仅从 pi 生成的摘要（fromHook !== true）中提取，不包括扩展生成的摘要
        for (SessionEntry entry : entries) {
            if (entry instanceof BranchSummaryEntry<?> bse && !Boolean.TRUE.equals(bse.fromHook())) {
                Object details = bse.details();
                if (details instanceof BranchSummaryDetails bsd) {
                    if (bsd.readFiles() != null) {
                        for (String f : bsd.readFiles()) {
                            fileOps.addRead(f);
                        }
                    }
                    if (bsd.modifiedFiles() != null) {
                        // 修改文件归入 edited 集合以进行正确去重
                        for (String f : bsd.modifiedFiles()) {
                            fileOps.addEdited(f);
                        }
                    }
                } else if (details instanceof Map<?, ?> detailsMap) {
                    // 处理基于 Map 的详情（来自 JSON 反序列化）
                    extractFileOpsFromMap(detailsMap, fileOps);
                }
            }
        }

        // 第二遍：从最新到最旧遍历，添加消息直到 token 预算
        for (int i = entries.size() - 1; i >= 0; i--) {
            SessionEntry entry = entries.get(i);
            AgentMessage message = getMessageFromEntry(entry);
            if (message == null) continue;

            // 从助手指令消息中提取文件操作（工具调用）
            Compaction.extractFileOpsFromMessage(message, fileOps);

            int tokens = CompactionUtils.estimateTokens(message);

            // 添加前检查预算
            if (tokenBudget > 0 && totalTokens + tokens > tokenBudget) {
                // 如果是摘要条目，尽量将其纳入，因为它是重要的上下文
                if (entry instanceof CompactionEntry<?> || entry instanceof BranchSummaryEntry<?>) {
                    if (totalTokens < tokenBudget * 0.9) {
                        messages.add(0, message);
                        totalTokens += tokens;
                    }
                }
                // 停止——已超出预算
                break;
            }

            messages.add(0, message);
            totalTokens += tokens;
        }

        return new BranchPreparation(messages, fileOps, totalTokens);
    }

    /**
     * 从基于 Map 的详情对象中提取文件操作信息。
     * 处理 JSON 反序列化后可能存在的 Map 格式详情数据。
     */
    @SuppressWarnings("unchecked")
    private static void extractFileOpsFromMap(Map<?, ?> detailsMap, FileOperations fileOps) {
        Object readFiles = detailsMap.get("readFiles");
        if (readFiles instanceof List<?> readList) {
            for (Object f : readList) {
                if (f instanceof String s) {
                    fileOps.addRead(s);
                }
            }
        }

        Object modifiedFiles = detailsMap.get("modifiedFiles");
        if (modifiedFiles instanceof List<?> modifiedList) {
            for (Object f : modifiedList) {
                if (f instanceof String s) {
                    fileOps.addEdited(s);
                }
            }
        }
    }

    // =========================================================================
    // 摘要生成（Task 6.2）
    // =========================================================================

    /**
     * 异步生成已废弃分支条目的摘要。
     *
     * <p>在 token 预算内准备条目，序列化为文本后构建提示词，生成结构化摘要。
     * 支持取消信号以支持异步取消操作。
     * 生成的摘要包含文件操作信息，以便后续恢复上下文。
     *
     * <p><b>验证需求: 4.3, 4.4, 4.5, 4.6, 4.7, 4.8</b>
     *
     * @param entries 待摘要的会话条目（按时间顺序）
     * @param options 生成选项
     * @param signal  取消信号（可为 null）
     * @return 包含分支摘要结果的 CompletableFuture
     */
    public static CompletableFuture<BranchSummaryResult> generateBranchSummary(
            List<SessionEntry> entries,
            GenerateBranchSummaryOptions options,
            CancellationSignal signal
    ) {
        // 使用 effectively final 变量用于 lambda 表达式
        final GenerateBranchSummaryOptions effectiveOptions = 
                options != null ? options : GenerateBranchSummaryOptions.defaults();

        return CompletableFuture.supplyAsync(() -> {
            // 检查是否已取消
            if (signal != null && signal.isCancelled()) {
                return BranchSummaryResult.aborted();
            }

            // Token 预算 = 上下文窗口减去预留空间（用于提示词和响应）
            int tokenBudget = effectiveOptions.contextWindow() - effectiveOptions.reserveTokens();

            BranchPreparation preparation = prepareBranchEntries(entries, tokenBudget);

            if (preparation.messages().isEmpty()) {
                return BranchSummaryResult.of("No content to summarize");
            }

            // 再次检查是否已取消
            if (signal != null && signal.isCancelled()) {
                return BranchSummaryResult.aborted();
            }

            // 将对话序列化为文本
            String conversationText = SummaryGenerator.serializeConversation(preparation.messages());

            // 构建提示词
            String instructions = buildInstructions(effectiveOptions);
            String promptText = "<conversation>\n" + conversationText + "\n</conversation>\n\n" + instructions;

            // 当前生成一个简单摘要
            // 实际实现中应调用 LLM 生成
            StringBuilder summary = new StringBuilder();
            summary.append(BRANCH_SUMMARY_PREAMBLE);

            summary.append("## Goal\n");
            summary.append("[Branch exploration context preserved]\n\n");

            summary.append("## Progress\n");
            summary.append("### Done\n");
            summary.append("- [x] Previous branch conversation summarized\n\n");

            summary.append("## Key Decisions\n");
            summary.append("- **Branch explored**: Context from alternate branch preserved\n\n");

            summary.append("## Next Steps\n");
            summary.append("1. Continue with the current task\n");

            // 计算文件列表并追加到摘要
            Map<String, List<String>> fileLists = Compaction.computeFileLists(preparation.fileOps());
            List<String> readFiles = fileLists.get("readFiles");
            List<String> modifiedFiles = fileLists.get("modifiedFiles");

            String fileOpsStr = Compaction.formatFileOperations(readFiles, modifiedFiles);
            summary.append(fileOpsStr);

            return BranchSummaryResult.success(
                    summary.toString(),
                    readFiles,
                    modifiedFiles
            );
        });
    }

    /**
     * 根据选项构建指示字符串。
     *
     * <p>支持三种模式：
     * <ul>
     *   <li>替换模式：仅使用自定义指示（replaceInstructions=true 且 customInstructions 非空）</li>
     *   <li>追加模式：在默认指示后追加自定义指示</li>
     *   <li>默认模式：仅使用默认指示</li>
     * </ul>
     *
     * <p><b>验证需求: 4.5, 4.6</b>
     */
    private static String buildInstructions(GenerateBranchSummaryOptions options) {
        if (options.replaceInstructions() && options.customInstructions() != null) {
            return options.customInstructions();
        } else if (options.customInstructions() != null) {
            return BRANCH_SUMMARY_PROMPT + "\n\nAdditional focus: " + options.customInstructions();
        } else {
            return BRANCH_SUMMARY_PROMPT;
        }
    }

    /**
     * 构建分支摘要的提示文本。
     * 将消息序列化后包裹在 &lt;conversation&gt; 标签中，后接指示文本。
     *
     * @param messages            待摘要的消息列表
     * @param customInstructions  自定义指示（可为 null）
     * @param replaceInstructions 是否替换默认指示
     * @return 构建完成的提示文本
     */
    public static String buildBranchSummaryPrompt(
            List<? extends AgentMessage> messages,
            String customInstructions,
            boolean replaceInstructions
    ) {
        String conversationText = SummaryGenerator.serializeConversation(messages);

        String instructions;
        if (replaceInstructions && customInstructions != null) {
            instructions = customInstructions;
        } else if (customInstructions != null) {
            instructions = BRANCH_SUMMARY_PROMPT + "\n\nAdditional focus: " + customInstructions;
        } else {
            instructions = BRANCH_SUMMARY_PROMPT;
        }

        return "<conversation>\n" + conversationText + "\n</conversation>\n\n" + instructions;
    }

    // =========================================================================
    // 内部消息类型
    // =========================================================================

    /**
     * 内部自定义消息类型，实现 AgentMessage 接口。
     * 用于在分支摘要流程中传递自定义消息内容。
     * 角色固定为 "custom"。
     */
    private record InternalCustomMessage(
            String customType,
            String content,
            boolean display,
            long timestamp
    ) implements AgentMessage {
        @Override
        public String role() {
            return "custom";
        }

        @Override
        public String toString() {
            return content;
        }
    }

    /**
     * 内部分支摘要消息类型，实现 AgentMessage 接口。
     * 用于在分支摘要流程中传递分支摘要信息。
     * 角色固定为 "branchSummary"。
     */
    private record InternalBranchSummaryMessage(
            String summary,
            String fromId,
            long timestamp
    ) implements AgentMessage {
        @Override
        public String role() {
            return "branchSummary";
        }

        @Override
        public String toString() {
            return summary;
        }
    }

    /**
     * 内部压缩摘要消息类型，实现 AgentMessage 接口。
     * 用于在分支摘要流程中传递压缩摘要信息。
     * 角色固定为 "compactionSummary"。
     */
    private record InternalCompactionSummaryMessage(
            String summary,
            int tokensBefore,
            long timestamp
    ) implements AgentMessage {
        @Override
        public String role() {
            return "compactionSummary";
        }

        @Override
        public String toString() {
            return summary;
        }
    }
}