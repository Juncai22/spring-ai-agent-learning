package com.pi.coding.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pi.agent.types.AgentTool;
import com.pi.agent.types.AgentToolResult;
import com.pi.agent.types.AgentToolUpdateCallback;
import com.pi.ai.core.types.CancellationSignal;
import com.pi.ai.core.types.ImageContent;
import com.pi.ai.core.types.TextContent;
import com.pi.ai.core.types.UserContentBlock;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Read 工具：读取文件内容，支持文本文件和图片。
 *
 * <p>该工具允许 AI 模型读取文件内容，是 Agent 了解代码和项目文件的主要方式。
 * 主要功能特性包括：
 * <ul>
 *   <li>文本文件读取：支持行号偏移和行数限制，便于分页读取大文件</li>
 *   <li>图片读取：支持 jpg、png、gif、webp 格式，以 Base64 编码附件形式返回</li>
 *   <li>自动截断：输出超过行数限制或字节数限制时自动截断</li>
 *   <li>续读提示：截断时自动提示下一部分的 offset 值，方便继续读取</li>
 *   <li>越界检测：当 offset 超出文件总行数时给出明确错误提示</li>
 * </ul>
 *
 * <p>验证需求：7.1-7.9
 */
public class ReadTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 常见图片文件扩展名到 MIME 类型的映射表。
     * 支持：JPEG、PNG、GIF、WebP
     */
    private static final Map<String, String> IMAGE_MIME_TYPES = Map.of(
        ".jpg", "image/jpeg",
        ".jpeg", "image/jpeg",
        ".png", "image/png",
        ".gif", "image/gif",
        ".webp", "image/webp"
    );

    /** 当前工作目录路径 */
    private final String cwd;
    /** 读取操作接口，支持本地或远程文件读取 */
    private final ReadOperations operations;
    /** 输出截断配置选项 */
    private final TruncationOptions truncationOptions;

    /**
     * 使用默认的本地读取操作与默认截断选项创建工具。
     *
     * @param cwd 当前工作目录路径
     */
    public ReadTool(String cwd) {
        this(cwd, new DefaultReadOperations(cwd), new TruncationOptions());
    }

    /**
     * 使用自定义的读取操作创建工具。
     *
     * @param cwd 当前工作目录路径
     * @param operations 自定义的读取操作实现
     */
    public ReadTool(String cwd, ReadOperations operations) {
        this(cwd, operations, new TruncationOptions());
    }

    /**
     * 使用完全自定义的参数创建读取工具。
     *
     * @param cwd 当前工作目录路径
     * @param operations 自定义的读取操作实现
     * @param truncationOptions 输出截断选项
     */
    public ReadTool(String cwd, ReadOperations operations, TruncationOptions truncationOptions) {
        this.cwd = cwd;
        this.operations = operations;
        this.truncationOptions = truncationOptions;
    }

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String description() {
        return String.format(
            "Read the contents of a file. Supports text files and images (jpg, png, gif, webp). " +
            "Images are sent as attachments. For text files, output is truncated to %d lines or %dKB " +
            "(whichever is hit first). Use offset/limit for large files.",
            truncationOptions.maxLines(),
            truncationOptions.maxBytes() / 1024
        );
    }

    @Override
    public JsonNode parameters() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode label = properties.putObject("label");
        label.put("type", "string");
        label.put("description", "Brief description of what you're reading and why (shown to user)");

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "Path to the file to read (relative or absolute)");

        ObjectNode offset = properties.putObject("offset");
        offset.put("type", "number");
        offset.put("description", "Line number to start reading from (1-indexed)");

        ObjectNode limit = properties.putObject("limit");
        limit.put("type", "number");
        limit.put("description", "Maximum number of lines to read");

        schema.putArray("required").add("label").add("path");

        return schema;
    }

    @Override
    public CompletableFuture<AgentToolResult<?>> execute(
            String toolCallId,
            JsonNode args,
            CancellationSignal signal,
            AgentToolUpdateCallback onUpdate) {

        String path = args.get("path").asText();
        Integer offset = args.has("offset") && !args.get("offset").isNull()
            ? args.get("offset").asInt() : null;
        Integer limit = args.has("limit") && !args.get("limit").isNull()
            ? args.get("limit").asInt() : null;

        String mimeType = getImageMimeType(path);

        if (mimeType != null) {
            // Read as image
            return readImage(path, mimeType, signal);
        } else {
            // Read as text
            return readText(path, offset, limit, signal);
        }
    }

    private CompletableFuture<AgentToolResult<?>> readImage(
            String path, String mimeType, CancellationSignal signal) {
        return operations.readBase64(path, signal)
            .thenApply(base64 -> {
                List<UserContentBlock> content = List.of(
                    new TextContent("Read image file [" + mimeType + "]"),
                    new ImageContent(base64, mimeType)
                );
                ReadToolDetails details = ReadToolDetails.forImage(path, mimeType);
                return new AgentToolResult<>(content, details);
            });
    }

    private CompletableFuture<AgentToolResult<?>> readText(
            String path, Integer offset, Integer limit, CancellationSignal signal) {
        
        int startLine = offset != null ? Math.max(1, offset) : 1;

        return operations.readText(path, offset, signal)
            .thenApply(result -> {
                int totalFileLines = result.totalLines();
                String selectedContent = result.content();

                // Check if offset is out of bounds
                if (startLine > totalFileLines) {
                    throw new RuntimeException(
                        String.format("Offset %d is beyond end of file (%d lines total)", 
                            offset, totalFileLines));
                }

                Integer userLimitedLines = null;

                // Apply user limit if specified
                if (limit != null) {
                    String[] lines = selectedContent.split("\n", -1);
                    int endLine = Math.min(limit, lines.length);
                    selectedContent = String.join("\n", 
                        java.util.Arrays.copyOfRange(lines, 0, endLine));
                    userLimitedLines = endLine;
                }

                // Apply truncation
                TruncationResult truncation = Truncation.truncateHead(selectedContent, truncationOptions);

                String outputText;
                ReadToolDetails details;

                if (truncation.firstLineExceedsLimit()) {
                    // First line at offset exceeds limit
                    String[] lines = selectedContent.split("\n", 2);
                    String firstLineSize = Truncation.formatSize(
                        lines[0].getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                    outputText = String.format(
                        "[Line %d is %s, exceeds %s limit. Use bash: sed -n '%dp' %s | head -c %d]",
                        startLine, firstLineSize, 
                        Truncation.formatSize(truncationOptions.maxBytes()),
                        startLine, path, truncationOptions.maxBytes());
                    details = ReadToolDetails.forText(path, totalFileLines, 0, null, truncation);
                } else if (truncation.truncated()) {
                    // Truncation occurred
                    int endLineDisplay = startLine + truncation.outputLines() - 1;
                    int nextOffset = endLineDisplay + 1;

                    outputText = truncation.content();

                    if ("lines".equals(truncation.truncatedBy())) {
                        outputText += String.format(
                            "\n\n[Showing lines %d-%d of %d. Use offset=%d to continue]",
                            startLine, endLineDisplay, totalFileLines, nextOffset);
                    } else {
                        outputText += String.format(
                            "\n\n[Showing lines %d-%d of %d (%s limit). Use offset=%d to continue]",
                            startLine, endLineDisplay, totalFileLines,
                            Truncation.formatSize(truncationOptions.maxBytes()), nextOffset);
                    }
                    details = ReadToolDetails.forText(path, totalFileLines, 
                        truncation.outputLines(), nextOffset, truncation);
                } else if (userLimitedLines != null) {
                    // User specified limit
                    int linesFromStart = startLine - 1 + userLimitedLines;
                    if (linesFromStart < totalFileLines) {
                        int remaining = totalFileLines - linesFromStart;
                        int nextOffset = startLine + userLimitedLines;

                        outputText = truncation.content();
                        outputText += String.format(
                            "\n\n[%d more lines in file. Use offset=%d to continue]",
                            remaining, nextOffset);
                        details = ReadToolDetails.forText(path, totalFileLines, 
                            userLimitedLines, nextOffset, truncation);
                    } else {
                        outputText = truncation.content();
                        details = ReadToolDetails.forText(path, totalFileLines, 
                            userLimitedLines, null, truncation);
                    }
                } else {
                    // No truncation
                    outputText = truncation.content();
                    details = ReadToolDetails.forText(path, totalFileLines, 
                        truncation.outputLines(), null, truncation);
                }

                List<UserContentBlock> content = List.of(new TextContent(outputText));
                return new AgentToolResult<>(content, details);
            });
    }

    /**
     * 根据文件扩展名判断是否为图片文件，并返回对应的 MIME 类型。
     * <p>
     * 支持的图片格式：.jpg、.jpeg、.png、.gif、.webp。
     * 如果文件扩展名不在支持列表中，返回 null 表示非图片文件。
     *
     * @param filePath 文件路径
     * @return 如果是支持的图片格式则返回 MIME 类型（如 "image/png"），否则返回 null
     */
    private static String getImageMimeType(String filePath) {
        int dotIndex = filePath.lastIndexOf('.');
        if (dotIndex < 0) {
            return null;
        }
        String ext = filePath.substring(dotIndex).toLowerCase(Locale.ROOT);
        return IMAGE_MIME_TYPES.get(ext);
    }
}
