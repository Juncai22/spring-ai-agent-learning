package com.pi.coding.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pi.agent.types.AgentTool;
import com.pi.agent.types.AgentToolResult;
import com.pi.agent.types.AgentToolUpdateCallback;
import com.pi.ai.core.types.CancellationSignal;
import com.pi.ai.core.types.TextContent;
import com.pi.ai.core.types.UserContentBlock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Edit 工具：通过精确文本替换编辑文件内容。
 *
 * <p>该工具允许 AI 模型对已有文件进行精确的文本替换操作，是 Agent 修改代码的核心工具。
 * 主要功能特性包括：
 * <ul>
 *   <li>精确匹配：默认使用精确文本匹配定位替换位置</li>
 *   <li>模糊匹配：当精确匹配失败时，自动降级到模糊匹配（忽略 Unicode 变体、智能引号等）</li>
 *   <li>行尾保护：自动检测并保留原始文件的换行风格（CRLF 或 LF）</li>
 *   <li>BOM 处理：正确处理 UTF-8 BOM（字节顺序标记）</li>
 *   <li>唯一性检查：确保替换文本在文件中唯一，防止误替换</li>
 *   <li>差异生成：替换后自动生成 Unified Diff 格式的变更摘要</li>
 * </ul>
 *
 * <p>验证需求：9.1-9.10
 */
public class EditTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 当前工作目录路径 */
    private final String cwd;
    /** 编辑操作接口，支持本地或远程文件编辑 */
    private final EditOperations operations;

    /**
     * 使用默认的本地文件操作创建编辑工具。
     *
     * @param cwd 当前工作目录路径
     */
    public EditTool(String cwd) {
        this(cwd, new DefaultEditOperations(cwd));
    }

    /**
     * 使用自定义的编辑操作创建工具，适用于远程或沙箱化文件编辑场景。
     *
     * @param cwd 当前工作目录路径
     * @param operations 自定义的编辑操作实现
     */
    public EditTool(String cwd, EditOperations operations) {
        this.cwd = cwd;
        this.operations = operations;
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "Edit a file by replacing exact text. The oldText must match exactly (including whitespace). " +
               "Use this for precise, surgical edits.";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "Path to the file to edit (relative or absolute)");

        ObjectNode oldText = properties.putObject("oldText");
        oldText.put("type", "string");
        oldText.put("description", "Exact text to find and replace (must match exactly)");

        ObjectNode newText = properties.putObject("newText");
        newText.put("type", "string");
        newText.put("description", "New text to replace the old text with");

        schema.putArray("required").add("path").add("oldText").add("newText");

        return schema;
    }

    @Override
    public CompletableFuture<AgentToolResult<?>> execute(
            String toolCallId,
            JsonNode args,
            CancellationSignal signal,
            AgentToolUpdateCallback onUpdate) {

        String path = args.get("path").asText();
        String oldText = args.get("oldText").asText();
        String newText = args.get("newText").asText();

        String absolutePath = resolvePath(path);

        // Check if file exists
        return operations.access(absolutePath, signal)
            .thenCompose(accessible -> {
                if (!accessible) {
                    throw new RuntimeException("File not found: " + path);
                }

                // Check cancellation
                if (signal != null && signal.isCancelled()) {
                    throw new RuntimeException("Operation aborted");
                }

                // Read the file
                return operations.readFile(absolutePath, signal);
            })
            .thenCompose(buffer -> {
                // Check cancellation
                if (signal != null && signal.isCancelled()) {
                    throw new RuntimeException("Operation aborted");
                }

                String rawContent = new String(buffer, StandardCharsets.UTF_8);

                // Strip BOM before matching
                EditDiff.BomResult bomResult = EditDiff.stripBom(rawContent);
                String bom = bomResult.bom();
                String content = bomResult.text();

                String originalEnding = EditDiff.detectLineEnding(content);
                String normalizedContent = EditDiff.normalizeToLF(content);
                String normalizedOldText = EditDiff.normalizeToLF(oldText);
                String normalizedNewText = EditDiff.normalizeToLF(newText);

                // Find the old text using fuzzy matching
                EditDiff.FuzzyMatchResult matchResult = EditDiff.fuzzyFindText(normalizedContent, normalizedOldText);

                if (!matchResult.found()) {
                    throw new RuntimeException(
                        "Could not find the exact text in " + path + 
                        ". The old text must match exactly including all whitespace and newlines.");
                }

                // Count occurrences using fuzzy-normalized content
                String fuzzyContent = EditDiff.normalizeForFuzzyMatch(normalizedContent);
                String fuzzyOldText = EditDiff.normalizeForFuzzyMatch(normalizedOldText);
                int occurrences = countOccurrences(fuzzyContent, fuzzyOldText);

                if (occurrences > 1) {
                    throw new RuntimeException(
                        "Found " + occurrences + " occurrences of the text in " + path + 
                        ". The text must be unique. Please provide more context to make it unique.");
                }

                // Check cancellation
                if (signal != null && signal.isCancelled()) {
                    throw new RuntimeException("Operation aborted");
                }

                // Perform replacement
                String baseContent = matchResult.contentForReplacement();
                String newContent = baseContent.substring(0, matchResult.index()) +
                    normalizedNewText +
                    baseContent.substring(matchResult.index() + matchResult.matchLength());

                // Verify the replacement actually changed something
                if (baseContent.equals(newContent)) {
                    throw new RuntimeException(
                        "No changes made to " + path + 
                        ". The replacement produced identical content. " +
                        "This might indicate an issue with special characters or the text not existing as expected.");
                }

                String finalContent = bom + EditDiff.restoreLineEndings(newContent, originalEnding);

                // Generate diff before writing
                EditDiff.DiffResult diffResult = EditDiff.generateDiffString(baseContent, newContent);

                // Write the file
                return operations.writeFile(absolutePath, finalContent, signal)
                    .thenApply(v -> {
                        // Check cancellation
                        if (signal != null && signal.isCancelled()) {
                            throw new RuntimeException("Operation aborted");
                        }

                        List<UserContentBlock> resultContent = List.of(
                            new TextContent("Successfully replaced text in " + path + ".")
                        );
                        EditToolDetails details = new EditToolDetails(
                            path, diffResult.firstChangedLine(), diffResult.diff());
                        return new AgentToolResult<>(resultContent, details);
                    });
            });
    }

    /**
     * 将路径解析为绝对路径：如果传入路径已经是绝对路径则直接返回，
     * 否则相对于当前工作目录进行解析。
     *
     * @param path 原始路径（相对或绝对）
     * @return 解析后的绝对路径字符串
     */
    private String resolvePath(String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) {
            return p.toString();
        }
        return Paths.get(cwd).resolve(path).toString();
    }

    /**
     * 统计字符串中子串出现的次数。
     * <p>
     * 用于检查要替换的文本在文件中是否唯一，防止因重复文本导致地误替换。
     * 如果文本出现多次，工具会抛出异常要求用户提供更多上下文。
     *
     * @param str 要搜索的完整字符串
     * @param sub 要统计的子串
     * @return 子串出现的次数，如果 sub 为空则返回 0
     */
    private static int countOccurrences(String str, String sub) {
        if (sub.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
