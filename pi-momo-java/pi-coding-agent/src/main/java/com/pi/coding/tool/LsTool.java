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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Ls 工具：列出目录内容。
 *
 * <p>该工具允许 AI 模型查看目录中的文件和子目录列表，是 Agent 了解项目结构的主要方式。
 * 主要功能特性包括：
 * <ul>
 *   <li>目录列表：列出指定目录下的所有文件和子目录</li>
 *   <li>目录标识：目录名后附加 '/' 后缀，便于区分文件和目录</li>
 *   <li>字母排序：结果按名称不区分大小写排序</li>
 *   <li>包含点文件：不隐藏以 '.' 开头的文件</li>
 *   <li>结果限制：默认最多返回 500 条记录</li>
 *   <li>字节数截断：超出字节数限制时自动截断</li>
 *   <li>路径验证：先检查路径是否存在，再检查是否为目录</li>
 * </ul>
 *
 * <p>验证需求：13.1-13.6
 */
public class LsTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 默认的最大返回条目数 */
    private static final int DEFAULT_LIMIT = 500;

    /** 当前工作目录路径 */
    private final String cwd;
    /** 列表操作接口，支持本地或远程目录列表 */
    private final LsOperations operations;
    /** 输出截断配置选项 */
    private final TruncationOptions truncationOptions;

    /**
     * 使用默认的本地列表操作与默认截断选项创建工具。
     *
     * @param cwd 当前工作目录路径
     */
    public LsTool(String cwd) {
        this(cwd, new DefaultLsOperations(cwd), new TruncationOptions());
    }

    /**
     * 使用自定义的列表操作创建工具。
     *
     * @param cwd 当前工作目录路径
     * @param operations 自定义的列表操作实现
     */
    public LsTool(String cwd, LsOperations operations) {
        this(cwd, operations, new TruncationOptions());
    }

    /**
     * 使用完全自定义的参数创建列表工具。
     *
     * @param cwd 当前工作目录路径
     * @param operations 自定义的列表操作实现
     * @param truncationOptions 输出截断选项
     */
    public LsTool(String cwd, LsOperations operations, TruncationOptions truncationOptions) {
        this.cwd = cwd;
        this.operations = operations;
        this.truncationOptions = truncationOptions;
    }

    @Override
    public String name() {
        return "ls";
    }

    @Override
    public String description() {
        return String.format(
            "List directory contents. Returns entries sorted alphabetically, with '/' suffix for directories. " +
            "Includes dotfiles. Output is truncated to %d entries or %dKB (whichever is hit first).",
            DEFAULT_LIMIT, truncationOptions.maxBytes() / 1024
        );
    }

    @Override
    public JsonNode parameters() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "Directory to list (default: current directory)");

        ObjectNode limit = properties.putObject("limit");
        limit.put("type", "number");
        limit.put("description", "Maximum number of entries to return (default: 500)");

        // No required fields
        schema.putArray("required");

        return schema;
    }

    @Override
    public CompletableFuture<AgentToolResult<?>> execute(
            String toolCallId,
            JsonNode args,
            CancellationSignal signal,
            AgentToolUpdateCallback onUpdate) {

        String dirPath = args.has("path") && !args.get("path").isNull() 
            ? args.get("path").asText() : ".";
        int limit = args.has("limit") && !args.get("limit").isNull() 
            ? args.get("limit").asInt() : DEFAULT_LIMIT;

        String absolutePath = resolvePath(dirPath);

        if (signal != null && signal.isCancelled()) {
            return CompletableFuture.failedFuture(new RuntimeException("Operation aborted"));
        }

        return operations.exists(absolutePath, signal)
            .thenCompose(exists -> {
                if (!exists) {
                    throw new RuntimeException("Path not found: " + dirPath);
                }
                return operations.isDirectory(absolutePath, signal);
            })
            .thenCompose(isDir -> {
                if (!isDir) {
                    throw new RuntimeException("Not a directory: " + dirPath);
                }
                return operations.readdir(absolutePath, signal);
            })
            .thenApply(entries -> {
                if (entries.isEmpty()) {
                    List<UserContentBlock> content = List.of(new TextContent("(empty directory)"));
                    return new AgentToolResult<>(content, null);
                }

                // Sort alphabetically (case-insensitive)
                entries.sort(Comparator.comparing(e -> e.name().toLowerCase()));

                // Format entries with directory indicators
                List<String> results = new ArrayList<>();
                boolean entryLimitReached = false;

                for (LsOperations.DirEntry entry : entries) {
                    if (results.size() >= limit) {
                        entryLimitReached = true;
                        break;
                    }
                    String suffix = entry.isDirectory() ? "/" : "";
                    results.add(entry.name() + suffix);
                }

                String rawOutput = String.join("\n", results);

                // Apply byte truncation
                TruncationResult truncation = Truncation.truncateHead(rawOutput, 
                    new TruncationOptions(Integer.MAX_VALUE, truncationOptions.maxBytes()));

                String output = truncation.content();
                List<String> notices = new ArrayList<>();

                if (entryLimitReached) {
                    notices.add(String.format("%d entries limit reached. Use limit=%d for more",
                        limit, limit * 2));
                }

                if (truncation.truncated()) {
                    notices.add(String.format("%s limit reached", Truncation.formatSize(truncationOptions.maxBytes())));
                }

                if (!notices.isEmpty()) {
                    output += "\n\n[" + String.join(". ", notices) + "]";
                }

                LsToolDetails details = new LsToolDetails(
                    dirPath, results.size(), truncation.truncated(),
                    entryLimitReached ? limit : null
                );

                List<UserContentBlock> content = List.of(new TextContent(output));
                return new AgentToolResult<>(content, details);
            });
    }

    private String resolvePath(String path) {
        Path p = Paths.get(path);
        if (p.isAbsolute()) {
            return p.toString();
        }
        return Paths.get(cwd).resolve(path).toString();
    }
}
