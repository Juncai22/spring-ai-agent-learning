package com.cloud.alibaba.ai.example.config;

// Note 1: ★★★ 这是整个 react-agent-example 的核心配置类。
// 它把所有零件 (ChatModel + 工具 + 拦截器 + 审批钩子 + 状态保存) 组装成一个 ReactAgent。
//
// ReactAgent 是 Spring AI Alibaba 提供的「ReAct 范式开箱即用实现」。
// 你不用自己写「思考-行动-观察」的循环, 框架内部已实现好, 你只管配置零件。
//
// 这个 Bean 装好之后, 整个 ReAct 循环就跑起来了:
//   LLM 思考 → 决定调工具 → [拦截器记日志] → [审批钩子暂停等用户] → 执行工具 → 结果回 LLM → 再思考...
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.cloud.alibaba.ai.example.interceptor.LogToolInterceptor;
import com.cloud.alibaba.ai.example.tools.FileReadTool;
import com.cloud.alibaba.ai.example.tools.FileWriteTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Note 2: @Configuration 标记这是配置类, 里面的 @Bean 方法会被 Spring 调用生成 Bean。
@Configuration
public class AgentConfiguration {

    // Note 3: 持有 ChatModel (LLM 引擎)。ReactAgent 需要它来做「思考」。
    // ChatModel 由 Spring Boot 自动装配注入 (DashScope starter 提供)。
    private final ChatModel chatModel;

    public AgentConfiguration(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    // Note 4: ★ 核心 Bean: ReactAgent。这是整个 Agent 的「大脑+身体」。
    // throws GraphStateException 是因为构建过程可能校验失败 (如配置不完整)。
    @Bean
    public ReactAgent reactAgent() throws GraphStateException {
        return ReactAgent.builder()
                // Note 5: name + description 是 Agent 的身份信息。
                // name:        内部标识, 多 Agent 协作时用来区分
                // description: 这个 Agent 干啥的, 在 Multi-Agent 场景下主 Agent 据此决定是否调用本 Agent
                .name("agent")
                .description("This is a react agent")
                // Note 6: ★ model —— 指定用哪个 LLM 做 ReAct 推理。
                // ReactAgent 的「思考」全靠这个 ChatModel, 没有它 Agent 就是个空壳。
                .model(chatModel)
                // Note 7: ★ saver —— 状态保存器, 用于「断点续跑」和「人工审批暂停」。
                // MemorySaver: 把 Agent 的执行状态存在内存里 (重启会丢)。
                //   生产环境换 JDBC/Redis Saver 可持久化。
                // 作用: 当 Agent 因审批暂停时, 状态存在 saver 里, 用户确认后从 saver 恢复继续执行。
                // 这就是 AgentController 里 threadId 能找回「上次暂停状态」的原理。
                .saver(new MemorySaver())
                // Note 8: ★ tools —— 挂载工具。ReactAgent 靠这些工具「行动」。
                // 注意: 这里传的是 ToolCallback (不是 Tool 对象本身)。
                //   new FileReadTool().toolCallback()  先 new 工具实例, 再调 toolCallback() 转成框架认识的句柄
                // 两个工具:
                //   file_read  读文件 (无需审批, LLM 可自由调)
                //   file_write 写文件 (后面配置成需要审批, 防止 LLM 乱写)
                .tools(
                        new FileReadTool().toolCallback(),
                        new FileWriteTool().toolCallback()
                )
                // Note 9: ★★★ hooks —— 人工介入钩子 (HumanInTheLoop, 简称 HITL)。
                // 这是本示例最关键的特性: 让 Agent 在「敏感操作」前暂停, 等用户确认。
                //
                // .approvalOn("file_write", "Write File should be approved"):
                //   当 LLM 决定调用 file_write 工具时, Agent 暂停执行,
                //   把「要写哪个文件、写什么内容」暴露给用户, 等用户批准/拒绝。
                //
                // 为什么需要这个:
                //   file_read 是只读, LLM 调了无害; file_write 会改文件系统, LLM 可能写错或乱写。
                //   关键操作让人把关, 是 Agent 走向生产的重要安全机制。
                .hooks(HumanInTheLoopHook.builder()
                        .approvalOn("file_write", "Write File should be approved")
                        .build())
                // Note 10: interceptors —— 工具调用拦截器。
                // 每次 LLM 调工具时, LogToolInterceptor 都会先记一行日志。
                // 多个拦截器可串联, 按顺序执行 (本例只有一个)。
                .interceptors(new LogToolInterceptor())
                // Note 11: build() 真正构造 ReactAgent 实例。
                // 到这里所有零件组装完毕, Agent 可以接收请求开始 ReAct 循环了。
                .build();
    }
}
