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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Bash 工具：在 Agent 工作目录中执行 Shell 命令。
 *
 * <p>该工具是 Agent 与底层操作系统交互的核心桥梁，允许 AI 模型通过 "bash" 工具调用
 * 来执行任意的 Shell 命令。主要功能特性包括：
 * <ul>
 *   <li>在指定的当前工作目录（cwd）中执行命令</li>
 *   <li>同时捕获标准输出（stdout）和标准错误输出（stderr）</li>
 *   <li>输出截断：超出行数限制或字节数限制时，自动截断并保存完整输出到临时文件</li>
 *   <li>支持可选的超时设置，防止命令长时间运行</li>
 *   <li>非零退出码自动转换为异常，确保 Agent 能感知到命令失败</li>
 * </ul>
 *
 * <p>验证需求：8.1-8.11
 */
public class BashTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 当前工作目录路径 */
    private final String cwd;
    /** Bash 操作接口，支持本地或远程/沙箱化执行 */
    private final BashOperations operations;
    /** 输出截断配置选项 */
    private final TruncationOptions truncationOptions;

    /**
     * 使用默认的本地 Bash 操作与默认截断选项创建工具。
     *
     * @param cwd 当前工作目录路径
     */
    public BashTool(String cwd) {
        this(cwd, new DefaultBashOperations(cwd), new TruncationOptions());
    }

    /**
     * 使用自定义的 Bash 操作与默认截断选项创建工具。
     * 适用于需要远程执行或沙箱化命令执行的场景。
     *
     * @param cwd 当前工作目录路径
     * @param operations 自定义的 Bash 操作实现
     */
    public BashTool(String cwd, BashOperations operations) {
        this(cwd, operations, new TruncationOptions());
    }

    /**
     * 使用完全自定义的参数创建 Bash 工具。
     *
     * @param cwd 当前工作目录路径
     * @param operations 自定义的 Bash 操作实现
     * @param truncationOptions 输出截断选项
     */
    public BashTool(String cwd, BashOperations operations, TruncationOptions truncationOptions) {
        this.cwd = cwd;
        this.operations = operations;
        this.truncationOptions = truncationOptions;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return String.format(
            "Execute a bash command in the current working directory. Returns stdout and stderr. " +
            "Output is truncated to last %d lines or %dKB (whichever is hit first). " +
            "If truncated, full output is saved to a temp file. Optionally provide a timeout in seconds.",
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
        label.put("description", "Brief description of what this command does (shown to user)");

        ObjectNode command = properties.putObject("command");
        command.put("type", "string");
        command.put("description", "Bash command to execute");

        ObjectNode timeout = properties.putObject("timeout");
        timeout.put("type", "number");
        timeout.put("description", "Timeout in seconds (optional, no default timeout)");

        schema.putArray("required").add("label").add("command");

        return schema;
    }

    @Override
    public CompletableFuture<AgentToolResult<?>> execute(
            String toolCallId,
            JsonNode args,
            CancellationSignal signal,
            AgentToolUpdateCallback onUpdate) {

        String command = args.get("command").asText();
        Integer timeout = args.has("timeout") && !args.get("timeout").isNull()
            ? args.get("timeout").asInt() : null;

        return operations.execute(command, timeout, signal)
            .thenApply(result -> {
                String output = result.combinedOutput();
                long totalBytes = output.getBytes(StandardCharsets.UTF_8).length;

                // Write to temp file if output exceeds limit
                String tempFilePath = null;
                if (totalBytes > truncationOptions.maxBytes()) {
                    tempFilePath = writeTempFile(output);
                }

                // Apply tail truncation
                TruncationResult truncation = Truncation.truncateTail(output, truncationOptions);
                String outputText = truncation.content().isEmpty() ? "(no output)" : truncation.content();

                BashToolDetails details = null;

                if (truncation.truncated()) {
                    // Ensure temp file exists for truncated output
                    if (tempFilePath == null) {
                        tempFilePath = writeTempFile(output);
                    }

                    details = BashToolDetails.truncated(command, result.exitCode(), tempFilePath, truncation);

                    // Build actionable notice
                    int startLine = truncation.totalLines() - truncation.outputLines() + 1;
                    int endLine = truncation.totalLines();

                    if (truncation.lastLinePartial()) {
                        // Edge case: last line alone > maxBytes
                        String[] lines = output.split("\n");
                        String lastLine = lines.length > 0 ? lines[lines.length - 1] : "";
                        String lastLineSize = Truncation.formatSize(
                            lastLine.getBytes(StandardCharsets.UTF_8).length);
                        outputText += String.format(
                            "\n\n[Showing last %s of line %d (line is %s). Full output: %s]",
                            Truncation.formatSize(truncation.outputBytes()),
                            endLine, lastLineSize, tempFilePath);
                    } else if ("lines".equals(truncation.truncatedBy())) {
                        outputText += String.format(
                            "\n\n[Showing lines %d-%d of %d. Full output: %s]",
                            startLine, endLine, truncation.totalLines(), tempFilePath);
                    } else {
                        outputText += String.format(
                            "\n\n[Showing lines %d-%d of %d (%s limit). Full output: %s]",
                            startLine, endLine, truncation.totalLines(),
                            Truncation.formatSize(truncationOptions.maxBytes()), tempFilePath);
                    }
                } else {
                    details = BashToolDetails.simple(command, result.exitCode(), totalBytes);
                }

                // Check exit code
                if (result.exitCode() != 0) {
                    String errorMessage = String.format(
                        "%s\n\nCommand exited with code %d",
                        outputText.trim(), result.exitCode());
                    throw new RuntimeException(errorMessage);
                }

                List<UserContentBlock> content = List.of(new TextContent(outputText));
                return new AgentToolResult<>(content, details);
            });
    }

    /**
     * 生成一个唯一的临时文件路径，用于保存 Bash 命令的完整输出。
     * 文件路径格式为：{系统临时目录}/pi-bash-{8字节随机十六进制}.log
     * 使用 SecureRandom 生成随机名称，防止文件名冲突。
     */
    private String getTempFilePath() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        String id = HexFormat.of().formatHex(bytes);
        return System.getProperty("java.io.tmpdir") + "/pi-bash-" + id + ".log";
    }

    /**
     * 将命令输出写入临时文件，当输出内容超过截断限制时使用。
     * 写入操作为静默处理：如果写入失败（如磁盘空间不足），返回 null 而非抛出异常，
     * 因为临时文件是可选优化，不影响核心功能。
     *
     * @param output 要写入的完整命令输出内容
     * @return 临时文件路径，如果写入失败则返回 null
     */
    private String writeTempFile(String output) {
        String tempFilePath = getTempFilePath();
        try {
            Files.writeString(Path.of(tempFilePath), output, StandardCharsets.UTF_8);
            return tempFilePath;
        } catch (IOException e) {
            // Log but don't fail - temp file is optional
            return null;
        }
    }
}
