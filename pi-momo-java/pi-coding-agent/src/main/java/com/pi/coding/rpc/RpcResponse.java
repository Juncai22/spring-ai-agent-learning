package com.pi.coding.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RPC 响应：通过标准输出以 JSON 行格式发送的响应。
 *
 * <p>每个命令执行后都会产生一个响应，包含操作结果或错误信息。
 * 通过 id 字段与请求命令关联。
 *
 * <p>响应格式：{"type": "response", "id": "命令ID", "command": "命令类型",
 * "success": true/false, "data": {}, "error": "错误信息"}
 *
 * <p>验证需求：20.2, 20.17
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RpcResponse(
        /** 关联的命令 ID，用于匹配请求与响应 */
        @JsonProperty("id") String id,
        /** 固定为 "response"，标识这是一个响应消息 */
        @JsonProperty("type") String type,
        /** 原始命令类型名称，如 "prompt", "set_model" */
        @JsonProperty("command") String command,
        /** 操作是否成功 */
        @JsonProperty("success") boolean success,
        /** 成功时的返回数据（可选） */
        @JsonProperty("data") Object data,
        /** 失败时的错误信息（可选） */
        @JsonProperty("error") String error
) {
    /**
     * 创建成功响应，包含返回数据。
     *
     * @param id      命令 ID
     * @param command 命令类型
     * @param data    返回数据
     * @return 成功响应
     */
    public static RpcResponse success(String id, String command, Object data) {
        return new RpcResponse(id, "response", command, true, data, null);
    }

    /**
     * 创建成功响应，不包含返回数据。
     *
     * @param id      命令 ID
     * @param command 命令类型
     * @return 成功响应
     */
    public static RpcResponse success(String id, String command) {
        return new RpcResponse(id, "response", command, true, null, null);
    }

    /**
     * 创建错误响应。
     *
     * @param id      命令 ID
     * @param command 命令类型
     * @param error   错误描述
     * @return 错误响应
     */
    public static RpcResponse error(String id, String command, String error) {
        return new RpcResponse(id, "response", command, false, null, error);
    }
}