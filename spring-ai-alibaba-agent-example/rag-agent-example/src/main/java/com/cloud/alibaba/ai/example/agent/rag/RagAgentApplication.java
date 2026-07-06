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
package com.cloud.alibaba.ai.example.agent.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RAG Agent Example Application
 * <p>
 * This application demonstrates the Agentic RAG (Retrieval Augmented Generation) pattern
 * using Spring AI Alibaba. The agent can autonomously retrieve relevant documents from
 * a knowledge base and generate comprehensive answers to user questions.
 * </p>
 *
 * @author zth9
 * @since 2026-01-22
 */
// Note 1: Spring Boot 启动类, rag-agent 模块入口。
// 启动时:
//   1. 自动装配 ChatModel + EmbeddingModel (DashScope starter 提供)
//   2. KnowledgeRetrievalTool @PostConstruct 建知识库索引 (抓文档→切块→向量化→存库)
//   3. RagAgentConfiguration 构建 ragAgent Bean
//   4. 启动 Tomcat, 暴露 /api/rag/chat 接口
//
// 访问: POST /api/rag/chat  body: {"message": "Spring AI 怎么配置?"}
// 或:  GET /api/rag/chat?message=Spring AI 怎么配置?
//
// Agent 会自主决定: 这个问题要不要查知识库 → 查 → 综合 → 回答
@SpringBootApplication
public class RagAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(RagAgentApplication.class, args);
	}

}
