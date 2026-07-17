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
 * Grep 工具：搜索文件内容中的文本模式。
 *
 * <p>该工具允许 AI 模型在项目中搜索文件内容，是 Agent 定位代码逻辑和查找引用的核心工具。
 * 主要功能特性包括：
 * <ul>
 *   <li>正则表达式搜索：支持标准的 Java 正则表达式语法</li>
 *   <li>字面量搜索：可将模式视为纯文本而非正则表达式</li>
 *   <li>大小写敏感/不敏感：支持切换大小写匹配模式</li>
 *   <li>Glob 文件过滤：可指定只搜索匹配特定 Glob 模式的文件</li>
 *   <li>上下文行显示：支持显示匹配行前后的上下文行</li>
 *   <li>结果限制：默认最多返回 100 条匹配结果</li>
 *   <li>行长截断：超过 500 字符的行自动截断</li>
 *   <li>自动忽略目录：自动跳过 node_modules 和 .git 目录</li>
 *   <li>单文件搜索：支持直接搜索单个文件而非整个目录</li>
 * </ul>
 *
 * <p>验证需求：11.1-11.7
 */
public class GrepTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 默认的最大匹配结果数 */
    private static final int DEFAULT_LIMIT = 100;
    /** 单行内容的最大显示长度，超出部分截断 */
    private static final int MAX_LINE_LENGTH = 500;

    /** 当前工作目录路径 */
    private final String cwd;
    /** Grep 操作接口，支持本地或远程文件内容搜索 */
    private final GrepOperations operations;
    /** 输出截断配置选项 */
    private final TruncationOptions truncationOptions;

    /**
     * 使用默认的本地 Grep 操作与默认截断选项创建工具。
     *
     * @param cwd 当前工作目录路径
     */
    public GrepTool(String cwd) {
        this(cwd, new DefaultGrepOperations(cwd), new TruncationOptions());
    }

    /**
     * 使用自定义的 Grep 操作创建工具。
     *
     * @param cwd 当前工作目录路径
     * @param operations 自定义的 Grep 操作实现
     */
    public GrepTool(String cwd, GrepOperations operations) {
        this(cwd, operations, new TruncationOptions());
    }

    /**
     * 使用完全自定义的参数创建 Grep 工具。
     *
     * @param cwd 当前工作目录路径
     * @param operations 自定义的 Grep 操作实现
     * @param truncationOptions 输出截断选项
     */
    public GrepTool(String cwd, GrepOperations operations, TruncationOptions truncationOptions) {
        this.cwd = cwd;
        this.operations = operations;
        this.truncationOptions = truncationOptions;
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return String.format(
            "Search file contents for a pattern. Returns matching lines with file paths and line numbers. " +
            "Respects .gitignore. Output is truncated to %d matches or %dKB (whichever is hit first). " +
            "Long lines are truncated to %d chars.",
            DEFAULT_LIMIT, truncationOptions.maxBytes() / 1024, MAX_LINE_LENGTH
        );
    }

    @Override
    public JsonNode parameters() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode pattern = properties.putObject("pattern");
        pattern.put("type", "string");
        pattern.put("description", "Search pattern (regex or literal string)");

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "Directory or file to search (default: current directory)");

        ObjectNode glob = properties.putObject("glob");
        glob.put("type", "string");
        glob.put("description", "Filter files by glob pattern, e.g. '*.ts' or '**/*.spec.ts'");

        ObjectNode ignoreCase = properties.putObject("ignoreCase");
        ignoreCase.put("type", "boolean");
        ignoreCase.put("description", "Case-insensitive search (default: false)");

        ObjectNode literal = properties.putObject("literal");
        literal.put("type", "boolean");
        literal.put("description", "Treat pattern as literal string instead of regex (default: false)");

        ObjectNode context = properties.putObject("context");
        context.put("type", "number");
        context.put("description", "Number of lines to show before and after each match (default: 0)");

        ObjectNode limit = properties.putObject("limit");
        limit.put("type", "number");
        limit.put("description", "Maximum number of matches to return (default: 100)");

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
        String glob = args.has("glob") && !args.get("glob").isNull() 
            ? args.get("glob").asText() : null;
        boolean ignoreCase = args.has("ignoreCase") && args.get("ignoreCase").asBoolean();
        boolean literal = args.has("literal") && args.get("literal").asBoolean();
        int context = args.has("context") && !args.get("context").isNull() 
            ? args.get("context").asInt() : 0;
        int limit = args.has("limit") && !args.get("limit").isNull() 
            ? args.get("limit").asInt() : DEFAULT_LIMIT;

        String searchPath = resolvePath(searchDir);

        if (signal != null && signal.isCancelled()) {
            return CompletableFuture.failedFuture(new RuntimeException("Operation aborted"));
        }

        GrepOperations.GrepOptions options = new GrepOperations.GrepOptions(
            glob, ignoreCase, literal, context, limit
        );

        return operations.grep(pattern, searchPath, options, signal)
            .thenCompose(result -> {
                if (result.matches().isEmpty()) {
                    List<UserContentBlock> content = List.of(new TextContent("No matches found"));
                    return CompletableFuture.completedFuture(new AgentToolResult<>(content, null));
                }

                // Format matches with context
                return formatMatches(result, searchPath, context, signal);
            });
    }

    private CompletableFuture<AgentToolResult<?>> formatMatches(
            GrepOperations.GrepResult result, String searchPath, int context, CancellationSignal signal) {
        
        List<String> outputLines = new ArrayList<>();
        boolean[] linesTruncated = {false};
        Path basePath = Paths.get(searchPath);

        for (GrepOperations.GrepMatch match : result.matches()) {
            String relativePath = formatPath(match.filePath(), basePath);
            String lineContent = match.lineContent();

            // Truncate long lines
            if (lineContent.length() > MAX_LINE_LENGTH) {
                lineContent = lineContent.substring(0, MAX_LINE_LENGTH) + "...";
                linesTruncated[0] = true;
            }

            outputLines.add(String.format("%s:%d: %s", relativePath, match.lineNumber(), lineContent));
        }

        // Apply byte truncation
        String rawOutput = String.join("\n", outputLines);
        TruncationResult truncation = Truncation.truncateHead(rawOutput, 
            new TruncationOptions(Integer.MAX_VALUE, truncationOptions.maxBytes()));

        String output = truncation.content();
        List<String> notices = new ArrayList<>();

        if (result.limitReached()) {
            notices.add(String.format("%d matches limit reached. Use limit=%d for more, or refine pattern",
                result.matches().size(), result.matches().size() * 2));
        }

        if (truncation.truncated()) {
            notices.add(String.format("%s limit reached", Truncation.formatSize(truncationOptions.maxBytes())));
        }

        if (linesTruncated[0]) {
            notices.add(String.format("Some lines truncated to %d chars. Use read tool to see full lines", MAX_LINE_LENGTH));
        }

        if (!notices.isEmpty()) {
            output += "\n\n[" + String.join(". ", notices) + "]";
        }

        GrepToolDetails details = new GrepToolDetails(
            "", result.matches().size(), truncation.truncated(),
            result.limitReached() ? result.matches().size() : null, linesTruncated[0]
        );

        List<UserContentBlock> content = List.of(new TextContent(output));
        return CompletableFuture.completedFuture(new AgentToolResult<>(content, details));
    }

    /**
     * 将文件路径格式化为相对于搜索基路径的显示路径。
     * <p>
     * 如果文件在搜索目录下，返回相对路径；否则仅返回文件名。
     * 路径分隔符统一替换为 '/'，确保跨平台一致性。
     *
     * @param filePath 文件的绝对路径
     * @param basePath 搜索基路径
     * @return 格式化后的显示路径
     */
    private String formatPath(String filePath, Path basePath) {
        try {
            Path path = Paths.get(filePath);
            if (path.startsWith(basePath)) {
                return basePath.relativize(path).toString().replace('\\', '/');
            }
            return path.getFileName().toString();
        } catch (Exception e) {
            return filePath;
        }
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
}
