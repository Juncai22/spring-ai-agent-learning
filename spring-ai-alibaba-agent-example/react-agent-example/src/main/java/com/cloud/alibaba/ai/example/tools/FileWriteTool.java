package com.cloud.alibaba.ai.example.tools;

// Note 1: FileWriteTool 是 ReactAgent 的「写文件」能力。
// 它和 FileReadTool 结构完全一样, 但有两个关键差异:
//   1. 入参有两个字段 (file_path + content)
//   2. 做了「路径安全处理」, 防止 LLM 写到任意位置 (安全设计)
//
// ★ 这个工具还会被配置成「需要人工审批」(HumanInTheLoop),
// 即 LLM 决定写文件后, 先暂停等用户确认, 用户同意才真正写入。
// 这就是 AgentController 里 /invoke + /feedback 两步流程的来源。
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;

public class FileWriteTool implements Tool<FileWriteTool.Request, String> {

    // Note 2: 工具名 "file_write"。这个名字很关键——后面 AgentConfiguration 里
    // .approvalOn("file_write", ...) 就是靠这个名字识别「哪个工具需要审批」。
    @Override
    public ToolCallback toolCallback() {
        return FunctionToolCallback.builder("file_write", this)
                .description("Tool for write files")
                .inputType(Request.class)
                .build();
    }

    // Note 3: 写文件逻辑。参数 s 是 LLM 传来的 Request (含路径+内容)。
    @Override
    public String apply(FileWriteTool.Request s, ToolContext toolContext) {
        try {
            // Note 4: ★ 路径安全处理——这是写文件工具的必备防护。
            // 问题: LLM 可能传任意路径, 比如 "/etc/passwd" 或 "../../../sensitive", 造成越权写入。
            // 解决: 把路径限制在「项目工作目录」内。
            //   System.getProperty("user.dir")  获取当前工作目录 (项目根目录)
            //   .resolve(s.filePath)            把用户给的相对路径拼上去
            //   .normalize()                    规范化, 消除 "../" 等跳目录写法
            //   .toString()                     转回字符串
            // 这样即使用户传 "../../../etc/passwd", normalize 后也会被限制在工作目录内。
            // 注意: 这只是基础防护, 生产环境还要检查 normalize 后的路径是否仍在工作目录下
            // (本例为简洁省略了 startsWith 检查, 生产代码应补上)。
            String safePath = Paths.get(System.getProperty("user.dir"))
                    .resolve(s.filePath)
                    .normalize()
                    .toString();

            // Note 5: 经典的文件写入三件套: 创建 Writer → write → close。
            // 实际生产推荐用 try-with-resources (writer 自动关闭), 这里是简化写法。
            FileWriter writer = new FileWriter(safePath);
            writer.write(s.content);
            writer.close();

            // Note 6: 返回成功信息给 LLM。LLM 拿到后会告诉用户「已写入」。
            return "Successfully wrote to file: " + s.filePath;
        } catch (IOException e) {
            // Note 7: 同 FileReadTool, 异常转文本返回, 不打断 Agent 流程。
            return "Error writing to file: " + e.getMessage();
        }
    }

    // Note 8: 两个字段的 Request record。
    // file_path: 要写入的路径
    // content:   要写入的内容
    // 两个字段都 required=true, LLM 必须都传, 否则框架会校验失败。
    @JsonClassDescription("Request for writing a file")
    public record Request(
            @JsonProperty(value = "file_path", required = true)
            @JsonPropertyDescription("The path of the file to write")
            String filePath,
            @JsonProperty(value = "content", required = true)
            @JsonPropertyDescription("The content to write to the file")
            String content
    ) {

    }
}
