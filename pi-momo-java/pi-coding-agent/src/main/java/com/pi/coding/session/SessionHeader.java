package com.pi.coding.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 会话头记录：存储在会话 JSONL 文件的第一行。
 *
 * <p>包含会话的元数据信息，包括版本号、唯一 ID、时间戳、
 * 工作目录，以及可选的父会话引用（用于分叉的会话）。
 *
 * <p>验证需求：1.1, 1.2
 *
 * @param type          固定为 "session"，标识此为头条目
 * @param version       会话格式版本号（当前为 {@link #CURRENT_VERSION}）
 * @param id            唯一会话标识符（UUID）
 * @param timestamp     会话创建时间的 ISO 8601 时间戳
 * @param cwd           启动会话时的工作目录
 * @param parentSession 如果此会话是分叉生成的，记录父会话文件路径（可为 null）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionHeader(
        @JsonProperty("type") String type,
        @JsonProperty("version") int version,
        @JsonProperty("id") String id,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("parentSession") String parentSession
) {
    /**
     * 当前会话格式版本号。
     */
    public static final int CURRENT_VERSION = 3;

    /**
     * 创建新的会话头，使用当前版本号。
     *
     * @param id            唯一会话标识符
     * @param timestamp     ISO 8601 时间戳
     * @param cwd           工作目录
     * @param parentSession 可选的父会话路径（可为 null）
     * @return 新的 SessionHeader 实例
     */
    public static SessionHeader create(String id, String timestamp, String cwd, String parentSession) {
        return new SessionHeader("session", CURRENT_VERSION, id, timestamp, cwd, parentSession);
    }

    /**
     * 创建新的会话头，不指定父会话。
     *
     * @param id        唯一会话标识符
     * @param timestamp ISO 8601 时间戳
     * @param cwd       工作目录
     * @return 新的 SessionHeader 实例
     */
    public static SessionHeader create(String id, String timestamp, String cwd) {
        return create(id, timestamp, cwd, null);
    }
}