/*
 * Copyright 2026-2027 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloud.alibaba.ai.example.agent;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;

// Note 1: HITLHelper 是「人工介入辅助工具类」——封装了三种审批操作的静态方法。
//
// 对比第6站 react-agent: 那里审批逻辑写在 Controller 里 (手动构建 APPROVED/REJECTED)。
// 本站把审批逻辑抽成工具类, 复用性更好。
//
// 三种审批操作:
//   approveAll:  批准所有工具调用
//   rejectAll:   拒绝所有工具调用 (附原因)
//   editTool:    编辑特定工具的参数 (改完再批准)
//
// 都返回 InterruptionMetadata, 用于恢复 Agent 执行 (传给 RunnableConfig.addHumanFeedback)。
public class HITLHelper {
    /**
     * 批准所有工具调用
     */
    // Note 2: ★ approveAll——把所有待审批的工具调用标记为 APPROVED。
    // 用法: Agent 暂停后, 用户全部同意, 调这个方法构建审批结果, 恢复执行。
    public static InterruptionMetadata approveAll(InterruptionMetadata interruptionMetadata) {
        InterruptionMetadata.Builder builder = InterruptionMetadata.builder()
                .nodeId(interruptionMetadata.node())      // 保留原节点 (恢复时从这里继续)
                .state(interruptionMetadata.state());      // 保留原状态

        // 遍历所有待审批工具, 全部标记 APPROVED
        interruptionMetadata.toolFeedbacks().forEach(toolFeedback -> {
            builder.addToolFeedback(
                    InterruptionMetadata.ToolFeedback.builder(toolFeedback)
                            .result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED)
                            .description("Agree to tool execution.")
                            .build()
            );
        });

        return builder.build();
    }

    /**
     * 拒绝所有工具调用
     */
    // Note 3: ★ rejectAll——把所有待审批工具标记为 REJECTED + 附原因。
    // Agent 恢复后不会执行这些工具, LLM 会收到「用户拒绝: 原因」, 改用其他方案或告知用户。
    public static InterruptionMetadata rejectAll(
            InterruptionMetadata interruptionMetadata,
            String reason) {
        InterruptionMetadata.Builder builder = InterruptionMetadata.builder()
                .nodeId(interruptionMetadata.node())
                .state(interruptionMetadata.state());

        interruptionMetadata.toolFeedbacks().forEach(toolFeedback -> {
            builder.addToolFeedback(
                    InterruptionMetadata.ToolFeedback.builder(toolFeedback)
                            .result(InterruptionMetadata.ToolFeedback.FeedbackResult.REJECTED)
                            .description(reason)   // ★ 附拒绝原因, 告诉 LLM 为啥被拒
                            .build()
            );
        });

        return builder.build();
    }

    /**
     * 编辑特定工具的参数
     */
    // Note 4: ★ editTool——最灵活的审批方式: 改工具参数后再批准。
    // 场景: LLM 想发邮件给张三, 用户审批时发现收件人错了 (应是李四),
    //       用 editTool 改收件人参数, 标记 EDITED, Agent 恢复后用新参数执行。
    //
    // 逻辑: 遍历工具, 匹配 toolName 的改成 EDITED+新参数, 其他的默认 APPROVED。
    public static InterruptionMetadata editTool(
            InterruptionMetadata interruptionMetadata,
            String toolName,
            String newArguments) {
        InterruptionMetadata.Builder builder = InterruptionMetadata.builder()
                .nodeId(interruptionMetadata.node())
                .state(interruptionMetadata.state());

        interruptionMetadata.toolFeedbacks().forEach(toolFeedback -> {
            if (toolFeedback.getName().equals(toolName)) {
                // 匹配的工具: 改参数 + 标记 EDITED
                builder.addToolFeedback(
                        InterruptionMetadata.ToolFeedback.builder(toolFeedback)
                                .arguments(newArguments)   // ★ 改成新参数
                                .result(InterruptionMetadata.ToolFeedback.FeedbackResult.EDITED)
                                .build()
                );
            } else {
                // 其他工具: 默认批准
                builder.addToolFeedback(
                        InterruptionMetadata.ToolFeedback.builder(toolFeedback)
                                .result(InterruptionMetadata.ToolFeedback.FeedbackResult.APPROVED)
                                .build()
                );
            }
        });

        return builder.build();
    }

}
