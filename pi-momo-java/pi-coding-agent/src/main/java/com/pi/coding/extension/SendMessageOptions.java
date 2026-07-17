package com.pi.coding.extension;

/**
 * 消息发送选项 —— 控制消息发送行为的配置。
 *
 * <p>通过此选项可以控制消息发送时的行为，包括：
 * <ul>
 *   <li>是否触发新的 Agent 轮次</li>
 *   <li>消息的投递方式</li>
 * </ul>
 *
 * @param triggerTurn 是否触发新的轮次
 * @param deliverAs   消息投递方式（"steer"、"followUp" 或 "nextTurn"）
 */
public record SendMessageOptions(
    Boolean triggerTurn,
    String deliverAs
) {

    /** 以引导方式投递，消息会立即触发 Agent 响应 */
    public static final String DELIVER_AS_STEER = "steer";
    /** 以跟进方式投递，作为当前上下文的补充 */
    public static final String DELIVER_AS_FOLLOW_UP = "followUp";
    /** 在下一轮次投递 */
    public static final String DELIVER_AS_NEXT_TURN = "nextTurn";

    /**
     * SendMessageOptions 的构建器。
     */
    public static class Builder {
        private Boolean triggerTurn;
        private String deliverAs;

        public Builder triggerTurn(Boolean triggerTurn) { this.triggerTurn = triggerTurn; return this; }

        public Builder deliverAs(String deliverAs) { this.deliverAs = deliverAs; return this; }

        public SendMessageOptions build() {
            return new SendMessageOptions(triggerTurn, deliverAs);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
