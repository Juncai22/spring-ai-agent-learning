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
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Find 工具：根据 Glob 模式搜索文件。
 *
 * <p>该工具允许 AI 模型通过 Glob 通配符模式在项目中搜索文件，是 Agent 定位代码文件的核心工具。
 * 主要功能特性包括：
 * <ul>
 *   <li>Glob 模式匹配：支持标准的 Glob 通配符（如 {@code *.ts}、{@code **/*.json}）</li>
 *   <li>自动忽略目录：自动跳过 {@code node_modules} 和 {@code .git} 目录</li>
 *   <li>结果限制：默认最多返回 1000 条结果，防止输出过大</li>
 *   <li>字节数截断：超出字节数限制时自动截断</li>
 *   <li>路径存在性检查：搜索前验证目标目录是否存在</li>
 *   <li>相对路径返回：结果相对于搜索目录返回，路径分隔符统一为 '/'</li>
 * </ul>
 *
 * <p>验证需求：12.1-12.6
 */
public class FindTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 默认的最大结果数 */
    private static final int DEFAULT_LIMIT = 1000;

    /** 当前工作目录路径 */
    private final String cwd;
    /** 查找操作接口，支持本地或远程文件搜索 */
    private final FindOperations operations;
    /** 输出截断配置选项 */
    private final TruncationOptions truncationOptions;

    /**
     * 使用默认的本地查找操作与默认截断选项创建工具。
     *
     * @param cwd 当前工作目录路径
     */
    public FindTool(String cwd) {
        this(cwd, new DefaultFindOperations(cwd), new TruncationOptions());
    }

    /**
     * 使用自定义的查找操作创建工具。
     *
     * @param cwd 当前工作目录路径
     * @param operations 自定义的查找操作实现
     */
    public FindTool(String cwd, FindOperations operations) {
        this(cwd, operations, new TruncationOptions());
    }

    /**
     * 使用完全自定义的参数创建查找工具。
     *
     * @param cwd 当前工作目录路径
     * @param operations 自定义的查找操作实现
     * @param truncationOptions 输出截断选项
     */
    public FindTool(String cwd, FindOperations operations, TruncationOptions truncationOptions) {
        this.cwd = cwd;
        this.operations = operations;
        this.truncationOptions = truncationOptions;
    }

    @Override
    public String name() {
        return "find";
    }

    @Override
    public String description() {
        return String.format(
            "Search for files by glob pattern. Returns matching file paths relative to the search directory. " +
            "Respects .gitignore. Output is truncated to %d results or %dKB (whichever is hit first).",
            DEFAULT_LIMIT, truncationOptions.maxBytes() / 1024
        );
    }

    @Override
    public JsonNode parameters() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode pattern = properties.putObject("pattern");
        pattern.put("type", "string");
        pattern.put("description", "Glob pattern to match files, e.g. '*.ts', '**/*.json', or 'src/**/*.spec.ts'");

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "Directory to search in (default: current directory)");

        ObjectNode limit = properties.putObject("limit");
        limit.put("type", "number");
        limit.put("description", "Maximum number of results (default: 1000)");

        schema.putArray("required").add("pattern");

        return schema;
    }

    @Override
    public CompletableFuture<AgentToolResult<?>> execute(
            String toolCallId,
            JsonNode args,
            CancellationSignal signal,
            AgentToolUpdateCallback onUpdate) {

        String pattern = args.get("pattern").asText();
        String searchDir = args.has("path") && !args.get("path").isNull() 
            ? args.get("path").asText() : ".";
        int limit = args.has("limit") && !args.get("limit").isNull() 
            ? args.get("limit").asInt() : DEFAULT_LIMIT;

        String searchPath = resolvePath(searchDir);

        if (signal != null && signal.isCancelled()) {
            return CompletableFuture.failedFuture(new RuntimeException("Operation aborted"));
        }

        return operations.exists(searchPath, signal)
            .thenCompose(exists -> {
                if (!exists) {
                    throw new RuntimeException("Path not found: " + searchPath);
                }

                List<String> ignore = List.of("**/node_modules/**", "**/.git/**");
                return operations.glob(pattern, searchPath, ignore, limit, signal);
            })
            .thenApply(results -> {
                if (results.isEmpty()) {
                    List<UserContentBlock> content = List.of(new TextContent("No files found matching pattern"));
                    return new AgentToolResult<>(content, null);
                }

                boolean resultLimitReached = results.size() >= limit;
                String rawOutput = String.join("\n", results);

                // Apply byte truncation
                TruncationResult truncation = Truncation.truncateHead(rawOutput, 
                    new TruncationOptions(Integer.MAX_VALUE, truncationOptions.maxBytes()));

                String output = truncation.content();
                List<String> notices = new ArrayList<>();

                if (resultLimitReached) {
                    notices.add(String.format("%d results limit reached. Use limit=%d for more, or refine pattern",
                        limit, limit * 2));
                }

                if (truncation.truncated()) {
                    notices.add(String.format("%s limit reached", Truncation.formatSize(truncationOptions.maxBytes())));
                }

                if (!notices.isEmpty()) {
                    output += "\n\n[" + String.join(". ", notices) + "]";
                }

                FindToolDetails details = new FindToolDetails(
                    pattern, results.size(), truncation.truncated(),
                    resultLimitReached ? limit : null
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
