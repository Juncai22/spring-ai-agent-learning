package com.pi.ai.core.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * 全局 Jackson ObjectMapper 配置，用于 pi-ai-java 中所有 JSON 序列化/反序列化操作。
 *
 * <p>配置了以下关键特性：
 * <ul>
 *   <li><b>FAIL_ON_UNKNOWN_PROPERTIES = false</b> — 反序列化时忽略未知属性，
 *       确保与 API 新增字段的前向兼容性</li>
 *   <li><b>READ_UNKNOWN_ENUM_VALUES_AS_NULL</b> — 遇到未知枚举值时设置为 null 而非抛出异常，
 *       优雅处理 Provider 返回的未知枚举值</li>
 *   <li><b>NON_NULL 序列化包含策略</b> — 序列化时自动忽略 null 值字段，减少 JSON 体积</li>
 * </ul>
 *
 * <p>对应 TypeScript 中的 {@code util/json.ts}（Jackson 的 Java 等效实现）。
 */
public final class PiAiJson {

    /**
     * 共享的线程安全 ObjectMapper 实例。
     *
     * <p>Jackson 的 ObjectMapper 是线程安全的，可以在整个应用中共用一个实例。
     * 使用 JsonMapper.builder() 构建，配置了反序列化和序列化特性。
     *
     * @see ObjectMapper Jackson 官方文档推荐复用 ObjectMapper 实例
     */
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            // ========== 反序列化配置（JSON -> Java 对象）==========
            // 1. 忽略未知属性：当 JSON 中包含 Java 类未定义的字段时，不抛出异常
            //    作用：当前向兼容——API 新增字段不会导致旧版本代码崩溃
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // 2. 未知枚举值设为 null：当 JSON 中的枚举值在 Java 枚举类中不存在时，
            //    设为 null 而非抛出 InvalidFormatException
            //    作用：Provider 可能新增枚举值，设为 null 后调用方可以优雅处理
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)

            // ========== 序列化配置（Java 对象 -> JSON）==========
            // 3. 排除 null 值字段：序列化时自动跳过值为 null 的字段
            //    作用：减小 JSON 体积，减少网络传输量，同时避免 null 值引起的前端解析问题
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private PiAiJson() {
        // 工具类，禁止实例化
    }
}