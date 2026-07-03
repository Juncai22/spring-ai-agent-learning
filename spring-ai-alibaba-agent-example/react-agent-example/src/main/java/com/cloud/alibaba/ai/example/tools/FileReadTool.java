package com.cloud.alibaba.ai.example.tools;

// Note 1: FileReadTool 是 ReactAgent 的「读文件」能力。
// 当用户问「帮我看看 a.txt 里写了什么」时, LLM 会决定调用这个工具。
//
// 这个类演示了「自定义 Tool 接口」的标准写法:
//   1. implements Tool<Request, String>  (输入 Request, 输出 String)
//   2. 实现 toolCallback()  暴露元数据给 LLM
//   3. 实现 apply()         真正干活的逻辑
//   4. 内部定义 Request record  描述入参结构 (LLM 据此填参数)
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// Note 2: implements Tool<Request, String> —— 输入是内部的 Request record, 输出是 String。
// 输出用 String 是因为 LLM 只能理解文本, 工具结果最终要喂回给 LLM 做下一步推理。
public class FileReadTool implements Tool<FileReadTool.Request, String> {

    // Note 3: 把自己(this)包装成 Spring AI 的 ToolCallback。
    // FunctionToolCallback.builder() 三个关键参数:
    //   "file_read"      工具名, LLM 在决策时用这个名字找工具 (必须符合命名规范)
    //   this             工具实例本身, 实现了 BiFunction, 框架会调它的 apply()
    //   .inputType()     入参类型, Spring AI 据此自动生成 JSON Schema 发给 LLM
    @Override
    public ToolCallback toolCallback() {
        return FunctionToolCallback.builder("file_read", this)
                .description("Tool for read files. ")
                .inputType(Request.class)
                .build();
    }

    // Note 4: 真正干活的逻辑。BiFunction 的 apply 方法。
    // 参数:
    //   request      LLM 传过来的入参 (含 file_path)
    //   toolContext  工具上下文 (本例没用到, 但接口要求必须接收)
    // 返回: 文件内容字符串, 会被框架塞回给 LLM 作为「观察结果」。
    @Override
    public String apply(FileReadTool.Request request, ToolContext toolContext) {
        try {
            // Note 5: Files.readString 是 Java 11+ 的便捷方法, 一次性读全文。
            // Path.of 把字符串转成路径。读到内容直接返回给 LLM。
            return Files.readString(Path.of(request.filePath));
        } catch (IOException e) {
            // Note 6: 工具异常处理的关键: 不要抛异常, 而是返回错误文本。
            // 因为抛异常会打断 Agent 流程; 返回错误文本让 LLM 自己判断怎么办
            // (LLM 可能会重试、换路径、或告诉用户文件不存在)。这是 Agent 设计的重要约定。
            return "Error reading file: " + e.getMessage();
        }
    }

    // Note 7: Request 是工具的「入参 schema 定义」, 用 Java 14+ 的 record 简洁声明。
    // 它会被 Spring AI 反射生成 JSON Schema, LLM 看到的描述类似:
    //   { "file_path": "string, The path of the file to read" }
    // 三个注解的作用:
    //   @JsonClassDescription  整个 Request 的描述 (LLM 看的整体说明)
    //   @JsonProperty          字段的 JSON 名 + 是否必需 (required=true 告诉 LLM 必须传)
    //   @JsonPropertyDescription 字段描述 (LLM 据此理解该传什么值)
    // 这些注解和 @ToolParam 作用一样, 但用于 record 字段, 是另一种写法。
    @JsonClassDescription("Request for the FileReadTool")
    public record Request(
            @JsonProperty(value = "file_path", required = true)
            @JsonPropertyDescription("The path of the file to read")
            String filePath
    ) {}
}
