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

package com.cloud.alibaba.ai.example.agent.config;

// Note 1: ★★★ AgentConfig 是 subagent-personal-assistant 的核心——演示 Supervisor 范式。
//
// Supervisor 范式 (本站) vs SequentialAgent (上一站 llm-auditor):
//   llm-auditor:  SequentialAgent 固定串联 (critic→reviser), 子 Agent 都跑
//   本站:         Supervisor 动态调度, 主 Agent 把子 Agent 当工具调, 按需调用
//
// 架构:
//   supervisor Agent (主)
//     ├─ 工具: calendar_agent (子 Agent 当工具)
//     ├─ 工具: email_agent (子 Agent 当工具)
//     └─ 工具: get_user_email_tool (普通工具)
//
//   calendar_agent (子)
//     ├─ create_calendar_event
//     ├─ get_available_time_slots
//     └─ get_current_date_time
//
//   email_agent (子)
//     └─ send_email
//
// ★ 关键: AgentTool.getFunctionToolCallback(agent) 把子 Agent 包装成 Tool!
//   supervisor 调 calendar_agent 工具时, 实际是运行整个 calendar ReactAgent。
//   这就是「Agent as Tool」——子 Agent 成为父 Agent 的工具。
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.cloud.alibaba.ai.example.agent.tool.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration class for setting up AI agents used in the personal assistant application.
 * This class defines and configures various agents including calendar scheduling,
 * email composition, and a supervisor agent that coordinates between them.
 *
 * @author wangjx
 * @since 2026-02-13
 */
@Configuration
public class AgentConfig {


    // Note 2: ★ 三个 Agent 的系统提示词 (角色定义)。
    // CALENDAR_AGENT_PROMPT: 日历助手, 解析自然语言时间为 ISO 格式, 查可用时段, 创建事件
    private final static String CALENDAR_AGENT_PROMPT = """
            You are a calendar scheduling assistant.
            Parse natural language scheduling requests (e.g., 'next Tuesday at 2pm')
            into proper ISO datetime formats.
            Use get_available_time_slots to check availability when needed.
            Use create_calendar_event to schedule events.
            Always confirm what was scheduled in your final response.
            """;

    // EMAIL_AGENT_PROMPT: 邮件助手, 撰写专业邮件, 提取收件人, 调 send_email
    private final static String EMAIL_AGENT_PROMPT = """
            You are an email assistant.
            Compose professional emails based on natural language requests.
            Extract recipient information and craft appropriate subject lines and body text.
            Use send_email to send the message.
            Always confirm what was sent in your final response.
            """;

    // Note 3: ★ SUPERVISOR_PROMPT: 主 Agent 角色——「分解请求, 协调多个工具」。
    // 关键: "use multiple tools in sequence" 告诉 supervisor 可以连续调多个子 Agent。
    // 例如用户说"查张三邮箱并发邮件", supervisor 会先调 get_user_email_tool 查邮箱, 再调 email_agent 发邮件。
    private final static String SUPERVISOR_PROMPT = """
            You are a helpful personal assistant.
            You can schedule calendar events and send emails.
            Break down user requests into appropriate tool calls and coordinate the results.
            When a request involves multiple actions, use multiple tools in sequence.
            """;

    private final DashScopeChatModel dashScopeChatModel;

    public AgentConfig(DashScopeChatModel dashScopeChatModel) {
        this.dashScopeChatModel = dashScopeChatModel;
    }

    // Note 4: ★★★ 核心 Bean: supervisorAgent —— 主 Agent (Supervisor)。
    @Bean("supervisorAgent")
    public ReactAgent reactAgent() {
        // 配置检查点保存器（人工介入需要检查点来处理中断）
        // Note 5: ★ MemorySaver——HITL 必备。暂停时存现场, 恢复时取现场。
        // 和第6站 react-agent 一样, 没它没法暂停/恢复。
        MemorySaver memorySaver = new MemorySaver();

        // Note 6: ★★★ AgentTool.getFunctionToolCallback——把子 Agent 包装成 Tool!
        //   AgentTool.getFunctionToolCallback(calendarAgent()) → ToolCallback
        //   这样 supervisor 可以像调普通工具一样调用 calendar_agent。
        //   调用时实际是运行整个 calendar ReactAgent (它有自己的工具和循环)。
        //
        // 这就是 Supervisor 范式的精髓:
        //   不是「父 Agent 调子 Agent 的方法」, 而是「子 Agent 整个变成父 Agent 的工具」。
        ToolCallback calendarAgent = AgentTool.getFunctionToolCallback(calendarAgent());
        ToolCallback emailAgent = AgentTool.getFunctionToolCallback(emailAgent());


        return ReactAgent.builder()
                .name("supervisor_agent")
                .model(dashScopeChatModel)
                .systemPrompt(SUPERVISOR_PROMPT)                              // ★ 主 Agent 角色
                .hooks(createHumanInTheLoopHook())                            // ★ HITL 审批钩子
                // Note 7: ★ supervisor 的工具 = 两个子 Agent (当工具) + 一个普通工具
                //   calendarAgent  → 调它运行 calendar 子 Agent
                //   emailAgent     → 调它运行 email 子 Agent
                //   get_user_email_tool → 查用户邮箱 (普通工具, 不需要子 Agent)
                // supervisor 根据用户意图, 动态决定调哪个/哪些工具 (不像 SequentialAgent 固定顺序)
                .tools(List.of(calendarAgent, emailAgent, new UserDataTool().toolCallback()))
                .saver(memorySaver)                                           // HITL 状态保存
                .build();
    }


    // Note 8: ★ emailAgent —— 邮件子 Agent。
    // 注意没有 @Bean (每次 calendarAgent() 调用时 new), 实际可加 @Bean 复用。
    public ReactAgent emailAgent() {

        String instruction =
                """
                        Send emails using natural language.
                        Use this when the user wants to send notifications, reminders, or any email
                        communication. Handles recipient extraction, subject generation, and email
                        composition.
                        Input: Natural language email request (e.g., 'send them a reminder about
                        the meeting')
                        """;
        // 创建 Agent
        return ReactAgent.builder()
                .name("email_agent")
                .model(dashScopeChatModel)
                .tools(List.of(new SendEmailTool().toolCallback()))   // ★ email Agent 只有 send_email 工具
                .systemPrompt(EMAIL_AGENT_PROMPT)
                .instruction(instruction)                              // ★ instruction: 当被 supervisor 当工具调时, 这个描述告诉 supervisor 何时调它
                .inputType(String.class)                               // ★ inputType: 子 Agent 接收 String 输入 (supervisor 传的自然语言)
                .build();
    }

    @Bean("calendarAgent")
    public ReactAgent calendarAgent() {

        String instruction = """
                Schedule calendar events using natural language.
                Use this when the user wants to create, modify, or check calendar appointments.
                Handles date/time parsing, availability checking, and event creation.
                Input: Natural language scheduling request (e.g., 'meeting with design team
                next Tuesday at 2pm')
                """;

        // 创建 Agent
        return ReactAgent.builder()
                .name("calendar_agent")
                .model(dashScopeChatModel)
                // Note 9: ★ calendar Agent 有 3 个工具:
                //   create_calendar_event  创建事件
                //   get_available_time_slots  查可用时段
                //   get_current_date_time  获取当前时间 (算"下周二"要用)
                .tools(List.of(new CreateCalendarEventTool().toolCallback(), new AvailableTimeSlotsTool().toolCallback(), new DateTimeTools().toolCallback()))
                .systemPrompt(CALENDAR_AGENT_PROMPT)
                .instruction(instruction)
                .inputType(String.class)
                .build();

    }

    // Note 10: ★★★ HITL Hook——给 calendar_agent 和 email_agent 配审批。
    // 注意: 这里 approvalOn 的是子 Agent 的名字 (calendar_agent/email_agent), 不是具体工具!
    // 因为 supervisor 把子 Agent 当工具调, 审批的是「要不要运行这个子 Agent」。
    //
    // 为什么审批子 Agent 而非具体工具:
    //   创建日历事件、发邮件都是敏感操作 (会真发邮件/真建事件)。
    //   在「运行子 Agent」这一层拦住, 子 Agent 内部的工具就不会执行。
    private HumanInTheLoopHook createHumanInTheLoopHook() {
        // 创建人工介入Hook
        return HumanInTheLoopHook.builder()
                .approvalOn("calendar_agent", ToolConfig.builder()
                        .description("Calendar event pending approval")
                        .build())
                .approvalOn("email_agent", ToolConfig.builder()
                        .description("Outbound email pending approval")
                        .build()).build();
    }
}
