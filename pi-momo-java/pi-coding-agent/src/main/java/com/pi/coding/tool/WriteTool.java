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
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Write 工具：将内容写入文件。
 *
 * <p>该工具允许 AI 模型创建新文件或覆盖已有文件，是 Agent 生成代码和创建文件的核心工具。
 * 主要功能特性包括：
 * <ul>
 *   <li>自动创建父目录：如果目标文件所在的目录不存在，会自动递归创建</li>
 *   <li>文件存在则覆盖：如果文件已存在，直接覆盖其全部内容</li>
 *   <li>文件不存在则创建：自动创建新文件</li>
 *   <li>取消支持：在写入前、写入中、写入后均检查取消信号</li>
 * </ul>
 *
 * <p>验证需求：10.1-10.4
 */
public class WriteTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 当前工作目录路径 */
    private final String cwd;
    /** 写入操作接口，支持本地或远程文件写入 */
    private final WriteOperations operations;

    /**
     * 使用默认的本地文件操作创建写入工具。
     *
     * @param cwd 当前工作目录路径
     */
    public WriteTool(String cwd) {
        this(cwd, new DefaultWriteOperations(cwd));
    }

    /**
     * 使用自定义的写入操作创建工具，适用于远程或沙箱化文件写入场景。
     *
     * @param cwd 当前工作目录路径
     * @param operations 自定义的写入操作实现
     */
    public WriteTool(String cwd, WriteOperations operations) {
        this.cwd = cwd;
        this.operations = operations;
    }

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String description() {
        return "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. " +
               "Automatically creates parent directories.";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "Path to the file to write (relative or absolute)");

        ObjectNode content = properties.putObject("content");
        content.put("type", "string");
        content.put("description", "Content to write to the file");

        schema.putArray("required").add("path").add("content");

        return schema;
    }

    @Override
    public CompletableFuture<AgentToolResult<?>> execute(
            String toolCallId,
            JsonNode args,
            CancellationSignal signal,
            AgentToolUpdateCallback onUpdate) {

        String path = args.get("path").asText();
        String content = args.get("content").asText();

        String absolutePath = resolvePath(path);
        String dir = Paths.get(absolutePath).getParent().toString();

        // Check if already aborted
        if (signal != null && signal.isCancelled()) {
            return CompletableFuture.failedFuture(new RuntimeException("Operation aborted"));
        }

        // Create parent directories if needed
        return operations.mkdir(dir, signal)
            .thenCompose(v -> {
                // Check if aborted before writing
                if (signal != null && signal.isCancelled()) {
                    throw new RuntimeException("Operation aborted");
                }

                // Write the file
                return operations.writeFile(absolutePath, content, signal);
            })
            .thenApply(v -> {
                // Check if aborted after writing
                if (signal != null && signal.isCancelled()) {
                    throw new RuntimeException("Operation aborted");
                }

                List<UserContentBlock> resultContent = List.of(
                    new TextContent("Successfully wrote " + content.length() + " bytes to " + path)
                );
                return new AgentToolResult<>(resultContent, null);
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
}
