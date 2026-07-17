package com.pi.ai.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.pi.ai.core.types.Tool;
import com.pi.ai.core.types.ToolCall;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具参数校验器，使用 JSON Schema 对 LLM 返回的 ToolCall 参数进行校验。
 *
 * <p>LLM 在调用工具时生成的参数可能不完全符合工具定义的 JSON Schema 规范，
 * 此校验器确保参数的有效性，并支持类型强制转换（coerceTypes）来容错。
 *
 * <p>核心功能：
 * <ul>
 *   <li>按工具名称查找对应的工具定义</li>
 *   <li>使用 JSON Schema 校验 ToolCall 的 arguments</li>
 *   <li>支持类型松散匹配（typeLoose），自动进行类型转换</li>
 *   <li>校验失败时提供详细的错误信息（字段路径 + 错误描述 + 原始参数）</li>
 * </ul>
 *
 * <p>使用 networknt/json-schema-validator 作为校验引擎，遵循 JSON Schema Draft 7 规范。
 *
 * <p>对应 TypeScript 中的 {@code utils/validation.ts}。
 */
public final class ToolValidator {

    /** JSON Schema 工厂实例，使用 Draft 7 版本 — 单例，线程安全 */
    private static final JsonSchemaFactory SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    private ToolValidator() {
        // 工具类，禁止实例化
    }

    /**
     * 按工具名称查找 Tool 定义并校验 ToolCall 的参数。
     *
     * <p>执行流程：
     * <ol>
     *   <li>在工具列表中查找与 toolCall.name() 匹配的工具定义</li>
     *   <li>如果未找到，抛出 IllegalArgumentException 说明工具不存在</li>
     *   <li>找到后调用 validateToolArguments 进行 JSON Schema 校验</li>
     * </ol>
     *
     * @param tools    工具定义列表，包含每个工具的 JSON Schema 参数规范
     * @param toolCall LLM 返回的工具调用，包含工具名称和参数
     * @return 校验通过的参数 Map（可能经过类型强制转换）
     * @throws IllegalArgumentException 如果工具未找到或参数校验失败
     */
    public static Map<String, Object> validateToolCall(List<Tool> tools, ToolCall toolCall) {
        // ========== 步骤 1：查找匹配的工具定义 ==========
        // 使用 stream 在工具列表中按名称精确匹配
        Tool tool = tools.stream()
                .filter(t -> t.name().equals(toolCall.name()))  // 按名称过滤
                .findFirst()                                      // 取第一个匹配项
                .orElseThrow(() -> new IllegalArgumentException(
                        "Tool \"" + toolCall.name() + "\" not found")); // 未找到时抛异常

        // ========== 步骤 2：委托给 validateToolArguments 进行校验 ==========
        return validateToolArguments(tool, toolCall);
    }

    /**
     * 校验 ToolCall 的参数是否符合 Tool 定义的 JSON Schema。
     *
     * <p>校验逻辑：
     * <ul>
     *   <li>如果工具没有定义参数 schema（parameters 为 null），直接返回参数，不做校验</li>
     *   <li>将参数转换为 JsonNode 后使用 JSON Schema 进行校验</li>
     *   <li>校验失败时，收集所有错误并格式化为可读的字符串，包含字段路径和错误描述</li>
     *   <li>错误信息中包含原始参数 JSON，方便调试</li>
     * </ul>
     *
     * @param tool     工具定义，包含 parameters（JSON Schema）规范
     * @param toolCall LLM 返回的工具调用
     * @return 校验通过的参数 Map
     * @throws IllegalArgumentException 如果校验失败，消息包含字段路径、错误描述和原始参数
     */
    public static Map<String, Object> validateToolArguments(Tool tool, ToolCall toolCall) {
        // ========== 前置检查：工具未定义参数 schema ==========
        // 如果 tool.parameters() 为 null，说明该工具没有参数校验要求
        // 直接返回 LLM 生成的参数，不做任何校验
        if (tool.parameters() == null) {
            return toolCall.arguments();
        }

        // ========== 步骤 1：准备校验数据 ==========
        // 将 arguments（Map<String, Object>）转换为 JsonNode
        // 因为 JSON Schema 校验引擎需要 JsonNode 作为输入
        JsonNode argsNode = PiAiJson.MAPPER.valueToTree(toolCall.arguments());

        // ========== 步骤 2：配置 JSON Schema 校验器 ==========
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                .typeLoose(true) // 启用类型松散模式（等价于 coerceTypes）
                // 作用：当 LLM 返回的字段类型与 Schema 定义不完全一致时，自动进行类型转换
                // 例如：Schema 要求 "string" 类型但接收到 number（如 123），
                //      或 Schema 要求 "integer" 但接收到 "42"（字符串数字），
                //      都会自动转换为目标类型，提高容错性
                .build();
        // 编译 JSON Schema：从工具定义的 parameters（JsonNode）创建可执行的 Schema 对象
        JsonSchema schema = SCHEMA_FACTORY.getSchema(tool.parameters(), config);

        // ========== 步骤 3：执行校验 ==========
        // validate 方法返回所有校验失败的 ValidationMessage 集合
        // 如果校验通过，集合为空
        Set<ValidationMessage> errors = schema.validate(argsNode);

        // ========== 步骤 4：校验通过 → 直接返回 ==========
        if (errors.isEmpty()) {
            return toolCall.arguments();
        }

        // ========== 步骤 5：校验失败 → 格式化错误信息 ==========
        // 将每个校验错误格式化为 "  - 字段路径: 错误描述" 的格式
        String errorDetails = errors.stream()
                .map(err -> {
                    // 获取错误发生的 JSON 路径（如 "$.properties.name"）
                    String path = err.getInstanceLocation().toString();
                    if (path.isEmpty()) {
                        path = "root"; // 根级别的错误（如整个参数类型不匹配）
                    }
                    return "  - " + path + ": " + err.getMessage();
                })
                .collect(Collectors.joining("\n"));

        // 将原始参数转换为美观的 JSON 字符串，方便用户查看原始输入
        String argsJson;
        try {
            // 使用带缩进的 pretty printer 格式序列化
            argsJson = PiAiJson.MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(toolCall.arguments());
        } catch (Exception e) {
            // 序列化失败时使用默认的 toString（兜底方案）
            argsJson = toolCall.arguments().toString();
        }

        // ========== 步骤 6：抛出包含详细校验信息的异常 ==========
        // 异常信息包含：工具名称、每个字段的校验错误、原始参数内容
        throw new IllegalArgumentException(
                "Validation failed for tool \"" + toolCall.name() + "\":\n"
                        + errorDetails + "\n\nReceived arguments:\n" + argsJson);
    }
}