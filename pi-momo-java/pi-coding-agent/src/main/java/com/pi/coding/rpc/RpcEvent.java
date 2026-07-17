package com.pi.coding.rpc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RPC 事件：通过标准输出以 JSON 行格式发送的事件。
 *
 * <p>包装 Agent 会话事件，供 RPC 消费者消费。
 * 当 Agent 会话中发生自动压缩、自动重试等事件时，
 * RpcMode 会将事件转换为 RpcEvent 发送到标准输出。
 *
 * <p>事件格式：{"type": "event", "event": "事件类型名", "data": {事件数据}}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RpcEvent(
        /** 固定为 "event"，标识这是一个事件消息 */
        @JsonProperty("type") String type,
        /** 事件类型名称（蛇形命名），如 "auto_compaction_start_event" */
        @JsonProperty("event") String event,
        /** 事件的具体数据对象 */
        @JsonProperty("data") Object data
) {
    /**
     * 创建 RPC 事件实例。
     *
     * @param eventType 事件类型名称
     * @param data      事件数据
     * @return RpcEvent 实例
     */
    public static RpcEvent of(String eventType, Object data) {
        return new RpcEvent("event", eventType, data);
    }
}