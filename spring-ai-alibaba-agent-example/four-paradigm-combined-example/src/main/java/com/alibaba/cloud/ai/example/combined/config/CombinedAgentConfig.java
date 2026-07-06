package com.alibaba.cloud.ai.example.combined.config;

import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.example.combined.tool.KnowledgeBaseTool;
import com.alibaba.cloud.ai.example.combined.tool.WebSearchTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * ★★★ 四范式合一核心配置 ★★★
 *
 * 本例演示一个「智能内容创作系统」, 同时使用四种 Agent 范式:
 *
 *   ① ReAct       —— 每个 Agent 都是 ReactAgent, 内部有「想-做-看」循环
 *   ② 并行        —— research_agent 同时调 web_search + knowledge_search (单 Agent 内多工具并行)
 *   ③ Reflection  —— supervisor 编排 writer→critic→reviser 循环, 直到质量满意
 *   ④ Supervisor  —— supervisor_agent 把子 Agent 当工具动态调度
 *
 * 完整流程:
 *   用户: "写一篇关于 Spring AI 的文章"
 *     ↓
 *   supervisor (Supervisor + ReAct)
 *     ├─ 调 research_agent: 并行查资料 (web + knowledge)  ← 并行
 *     ├─ 调 writer_agent: 写初稿                          ← ReAct
 *     ├─ 调 critic_agent: 审查                            ← Reflection
 *     ├─ 调 reviser_agent: 修订 (如果 critic 不满意)      ← Reflection 循环
 *     └─ 再调 critic_agent 复查... 直到满意
 *     ↓
 *   返回终稿
 */
@Configuration
public class CombinedAgentConfig {

    private final ChatModel chatModel;

    public CombinedAgentConfig(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    // ============ 四个 Agent 的系统提示词 ============

    /** research_agent: 资料研究员, 同时用 web + 知识库查资料 */
    private static final String RESEARCH_PROMPT = """
            你是资料研究员。接到主题后, 同时使用 web_search 和 knowledge_search 两个工具
            查询资料 (一个查互联网, 一个查本地知识库), 汇总后返回研究要点。
            """;

    /** writer_agent: 作家, 根据资料写初稿 */
    private static final String WRITER_PROMPT = """
            你是技术作家。根据提供的研究资料, 撰写一篇结构清晰、内容准确的技术文章初稿。
            """;

    /** critic_agent: 审稿人, 挑毛病 (Reflection 的审查者) */
    private static final String CRITIC_PROMPT = """
            你是严格的审稿人。审查文章的准确性、完整性、逻辑性。
            输出审查结论: 如果文章合格, 明确说"审查通过"; 如果不合格, 列出具体问题。
            """;

    /** reviser_agent: 编辑, 按审查意见修订 (Reflection 的修订者) */
    private static final String REVISER_PROMPT = """
            你是专业编辑。根据审稿人的意见修订文章, 解决所有提出的问题。
            输出修订后的完整文章。
            """;

    /**
     * ★ Supervisor 主 Agent —— 范式④: Supervisor + 范式①: ReAct
     *
     * supervisor 是 ReactAgent (ReAct 循环), 把 4 个子 Agent 当工具动态调度 (Supervisor)。
     * 它的 prompt 指导它编排 Reflection 循环: write→critique→revise→critique。
     */
    private static final String SUPERVISOR_PROMPT = """
            你是内容创作主管。用户让你写文章时, 按以下流程协调:

            1. 先调 research_agent 查资料
            2. 再调 writer_agent 写初稿
            3. 调 critic_agent 审查
            4. 如果 critic 说"审查通过", 结束并返回终稿
            5. 如果 critic 说有问题, 调 reviser_agent 修订, 然后回到第3步再审查 (Reflection 循环)
            6. 最多重试 3 次, 避免死循环

            你要根据每一步的结果动态决定下一步, 这是你的核心职责。
            """;

    // ============ 子 Agent 定义 ============

    /**
     * research_agent —— 范式① ReAct + 范式② 并行
     *
     * 它有两个工具 (web_search + knowledge_search), LLM 会在一次决策中同时调两个,
     * 框架并行执行——这就是「单 Agent 内的多工具并行」。
     */
    public ReactAgent researchAgent() {
        return ReactAgent.builder()
                .name("research_agent")
                .model(chatModel)
                .systemPrompt(RESEARCH_PROMPT)
                // ★ 两个工具: LLM 会并行调用, 体现并行范式
                .tools(List.of(
                        new WebSearchTool().toolCallback(),
                        new KnowledgeBaseTool().toolCallback()))
                .instruction("查询指定主题的资料, 同时用 web_search 和 knowledge_search。")
                .inputType(String.class)
                .outputKey("research_output")
                .build();
    }

    /** writer_agent —— 范式① ReAct (无工具, 一次生成) */
    public ReactAgent writerAgent() {
        return ReactAgent.builder()
                .name("writer_agent")
                .model(chatModel)
                .systemPrompt(WRITER_PROMPT)
                .instruction("根据研究资料撰写技术文章。")
                .inputType(String.class)
                .outputKey("writer_output")
                .build();
    }

    /** critic_agent —— 范式③ Reflection (审查者) */
    public ReactAgent criticAgent() {
        return ReactAgent.builder()
                .name("critic_agent")
                .model(chatModel)
                .systemPrompt(CRITIC_PROMPT)
                .instruction("审查文章质量, 决定是否通过。")
                .inputType(String.class)
                .outputKey("critic_output")
                .build();
    }

    /** reviser_agent —— 范式③ Reflection (修订者) */
    public ReactAgent reviserAgent() {
        return ReactAgent.builder()
                .name("reviser_agent")
                .model(chatModel)
                .systemPrompt(REVISER_PROMPT)
                .instruction("按审查意见修订文章。")
                .inputType(String.class)
                .outputKey("reviser_output")
                .build();
    }

    // ============ Supervisor 主 Agent ============

    /**
     * ★★★ supervisorAgent —— 四范式合一的核心
     *
     * 范式④ Supervisor: 把 4 个子 Agent 当工具, 动态调度
     * 范式① ReAct:      supervisor 自己是 ReactAgent, 有多步推理循环
     * 范式③ Reflection: prompt 指导它编排 critic→reviser 循环 (质量驱动)
     * 范式② 并行:       research_agent 内部并行调两个工具
     */
    @Bean("supervisorAgent")
    public ReactAgent supervisorAgent() {
        // ★ 范式④: AgentTool.getFunctionToolCallback 把子 Agent 包装成工具
        ToolCallback researchTool = AgentTool.getFunctionToolCallback(researchAgent());
        ToolCallback writerTool = AgentTool.getFunctionToolCallback(writerAgent());
        ToolCallback criticTool = AgentTool.getFunctionToolCallback(criticAgent());
        ToolCallback reviserTool = AgentTool.getFunctionToolCallback(reviserAgent());

        return ReactAgent.builder()
                .name("supervisor_agent")
                .model(chatModel)
                .systemPrompt(SUPERVISOR_PROMPT)
                // ★ 范式④: supervisor 的工具 = 4 个子 Agent (Agent as Tool)
                .tools(List.of(researchTool, writerTool, criticTool, reviserTool))
                .build();
    }
}
