/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.example.rag.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 */

@RestController
@RequestMapping("/module-rag")
public class ModuleRAGBasicController {

	private static final double SIMILARITY_THRESHOLD = 0.50;

	private final ChatClient chatClient;

	private final RetrievalAugmentationAdvisor retrievalAugmentationAdvisor;

	public ModuleRAGBasicController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {

		this.chatClient = chatClientBuilder.build();
		// RetrievalAugmentationAdvisor 是传统 RAG 的核心入口。
		// 它会在真正调用模型之前先执行检索，把检索到的文档片段拼进 Prompt，
		// 所以业务代码只需要像普通 ChatClient 一样传入用户问题。
		this.retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
			.documentRetriever(
					// VectorStoreDocumentRetriever 负责从向量库中找相似文档。
					// similarityThreshold 越高，召回越严格；越低，召回更多但噪声也可能更多。
					VectorStoreDocumentRetriever.builder()
						.similarityThreshold(SIMILARITY_THRESHOLD)
						.vectorStore(vectorStore)
						.build())
			.build();
	}

	@GetMapping("/rag/basic")
	public String chatWithDocument(@RequestParam("prompt") String prompt) {

		// advisors(retrievalAugmentationAdvisor) 把 RAG 检索链路挂到本次请求上。
		// 请求进入 Advisor 后，会经历：用户问题 -> 向量检索 -> 文档片段注入 -> LLM 生成答案。
		// Controller 看起来很薄，是因为检索和 Prompt 增强都被 Advisor 封装掉了。
		return chatClient.prompt()
			.advisors(retrievalAugmentationAdvisor)
			.user(prompt)
			.call()
			.content();
	}

}
