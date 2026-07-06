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
package com.cloud.alibaba.ai.example.agent.rag.config;

// Note 1: ★★ RagAgentConfiguration 是本模块的核心——演示「Agentic RAG」模式。
//
// ★ 最关键的认知: 看这个配置有多简单!
//   就是一个 ReactAgent + 一个知识检索工具。
//   和第6站 react-agent (ReactAgent + file_read/file_write) 结构完全一样!
//   唯一区别: 工具从「文件操作」换成「知识检索」。
//
// 这证明了: RAG 不是独立范式, 是 ReAct + 检索工具!
//   传统 RAG: 固定流程 (问题 → 必检索 → 生成), 每次都检索
//   Agentic RAG (本例): Agent 自主决定要不要检索
//     - 简单问题 (如"你好") → 不检索, 直接答
//     - 知识问题 (如"Spring AI 怎么配置") → 检索 → 答
//     - 复杂问题 → 可能检索多次, 每次查不同关键词
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.cloud.alibaba.ai.example.agent.rag.tool.KnowledgeRetrievalTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the RAG Agent
 * <p>
 * This configuration sets up a ReactAgent with knowledge retrieval capabilities,
 * implementing the Agentic RAG pattern. The agent can autonomously decide when
 * to query the knowledge base and how to synthesize responses.
 * </p>
 *
 * @author zth9
 * @since 2026-01-22
 */
@Configuration
public class RagAgentConfiguration {

    private final ChatModel chatModel;

    // Note 2: 注入 KnowledgeRetrievalTool (@Component 注册的 Bean)。
    // 这个工具在 @PostConstruct 时已经建好知识库索引, 这里直接用。
    private final KnowledgeRetrievalTool knowledgeRetrievalTool;

    public RagAgentConfiguration(ChatModel chatModel, KnowledgeRetrievalTool knowledgeRetrievalTool) {
        this.chatModel = chatModel;
        this.knowledgeRetrievalTool = knowledgeRetrievalTool;
    }

    // Note 3: ★★ 核心 Bean: ragAgent —— 一个挂着知识检索工具的 ReactAgent。
    @Bean
    public ReactAgent ragAgent() throws GraphStateException {
        return ReactAgent.builder()
            .name("rag-agent")
            // Note 4: description 详细描述 Agent 能力——多 Agent 场景下别的 Agent 据此决定是否调它。
            // 这里说明: 这个 Agent 能查 Spring AI Alibaba 文档知识库。
            .description("A RAG (Retrieval Augmented Generation) agent that can answer questions "
                    + "about Spring AI Alibaba by retrieving relevant documentation from "
                    + "the knowledge base. The agent uses semantic search to find the most "
                    + "relevant information and synthesizes comprehensive answers.")
            .model(chatModel)                                  // LLM 大脑
            .saver(new MemorySaver())                           // 状态保存 (支持多轮/暂停)
            // Note 5: ★★ 唯一的工具就是知识检索!
            //   对比第6站: .tools(fileReadTool, fileWriteTool)
            //   本站:      .tools(knowledgeRetrievalTool)
            //   结构完全一样, 只是工具换成了「查知识库」。
            // Agent 内部 ReAct 循环: LLM 决定调不调 knowledge_retrieval → 检索 → 看结果 → 答或再检索
            .tools(knowledgeRetrievalTool.toolCallback())
            .build();
    }

}
