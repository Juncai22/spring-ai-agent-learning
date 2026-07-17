package com.pi.coding.resource;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 资源加载过程中的诊断信息。
 *
 * <p>诊断信息用于记录资源加载过程中的各种异常情况，包括：
 * <ul>
 *   <li><b>warning（警告）</b> — 资源路径不存在、文件读取失败、名称校验不通过等</li>
 *   <li><b>collision（冲突）</b> — 资源名称重复导致后续资源被忽略</li>
 * </ul>
 *
 * <p>诊断信息会被序列化为 JSON 供外部消费，使用 {@link JsonInclude#NON_NULL}
 * 确保空字段不参与序列化。
 *
 * @param type      诊断类型，目前支持 "warning" 和 "collision"
 * @param message   诊断描述信息
 * @param path      相关资源文件的路径
 * @param collision 冲突详情（仅在 type 为 "collision" 时非空）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceDiagnostic(
    String type,
    String message,
    String path,
    ResourceCollision collision
) {
    /**
     * 创建一个不带冲突详情的诊断信息。
     *
     * @param type    诊断类型
     * @param message 诊断描述
     * @param path    相关文件路径
     */
    public ResourceDiagnostic(String type, String message, String path) {
        this(type, message, path, null);
    }

    /**
     * 紧凑构造函数，进行参数校验。
     *
     * @throws IllegalArgumentException 如果 type、message 或 path 为空
     */
    public ResourceDiagnostic {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("type 不能为空");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path 不能为空");
        }
    }
}
