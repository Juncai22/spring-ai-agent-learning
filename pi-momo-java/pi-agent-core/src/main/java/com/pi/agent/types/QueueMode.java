package com.pi.agent.types;

/**
 * 控制如何从引导队列（steering queue）和跟进队列（follow-up queue）中出队消息。
 *
 * <ul>
 *   <li>{@link #ALL} — 一次性出队所有待处理的消息。</li>
 *   <li>{@link #ONE_AT_A_TIME} — 每轮只出队一条消息。</li>
 * </ul>
 */
public enum QueueMode {

    /** 一次性出队所有待处理的消息。 */
    ALL,

    /** 每轮只出队一条消息。 */
    ONE_AT_A_TIME
}