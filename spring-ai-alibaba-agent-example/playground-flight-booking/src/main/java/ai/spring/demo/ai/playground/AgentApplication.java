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

package ai.spring.demo.ai.playground;

// Note 1: ★★ AgentApplication 既是启动类, 也是「装配中心」——声明三个核心 Bean 构成 AI 客服基础设施。
//
// 三个核心 Bean:
//   1. VectorStore (向量库)     —— RAG 检索的存储
//   2. ChatMemory (对话记忆)    —— 多轮对话的 history
//   3. CommandLineRunner        —— 启动时把条款文档灌入向量库 (RAG 数据准备)
//
// DashScope 的 ChatModel/EmbeddingModel 由 starter 自动装配, 这里不用声明。
// 这三个 Bean 会被 CustomerSupportAssistant 注入使用。
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.web.client.RestClient;

/**
 * 应用启动类，也是整个"四合一"架构的 <b>装配中心</b>。
 * <p>
 * 在此声明三个核心 Bean，构成 AI 客服的运行时基础设施：
 * <ul>
 *   <li>{@link VectorStore}（{@code SimpleVectorStore}）：内存向量库，承载 RAG 检索</li>
 *   <li>{@link ChatMemory}（{@code MessageWindowChatMemory}）：多轮对话记忆</li>
 *   <li>{@code CommandLineRunner}：启动时把条款文档切分后灌入向量库（RAG 数据准备）</li>
 * </ul>
 * DashScope 作为底层 ChatModel/EmbeddingModel 由 starter 自动装配，无需在此声明。
 */
@SpringBootApplication
public class AgentApplication  {

	private static final Logger logger = LoggerFactory.getLogger(AgentApplication.class);

	// Note 2: main 方法——用 SpringApplicationBuilder 启动 (比 SpringApplication.run 多些控制能力, 这里效果一样)。
	public static void main(String[] args) {
		new SpringApplicationBuilder(AgentApplication.class).run(args);
	}

	// In the real world, ingesting documents would often happen separately, on a CI
	// server or similar.
	// Note 3: ★★★ CommandLineRunner——启动时自动执行, 把条款文档灌入向量库。
	// 这是 RAG 的「数据准备」阶段, 在应用启动时就完成索引建立。
	// 参数:
	//   vectorStore          向量库 Bean (下面 vectorStore() 方法建的)
	//   termsOfServiceDocs   条款文档资源 (classpath:rag/terms-of-service.txt)
	@Bean
	CommandLineRunner ingestTermOfServiceToVectorStore(
			VectorStore vectorStore,
			@Value("classpath:rag/terms-of-service.txt") Resource termsOfServiceDocs
	) {

		return args -> {
			// Ingest the document into the vector store
			/*
			 * 1、文档读取TextReader 读取 resources/rag/terms-of-service.txt 文件内容
			 * 2、TokenTextSplitter 按token长度切分文本（避免大文本超出模型限制）
			 * 3、向量化存储 通过 VectorStore.write() 将文本向量存入内存（后续可用于RAG检索）
			 */
			// Note 4: ★ 一行链式完成 RAG 数据准备三步:
			//   new TextReader(termsOfServiceDocs).read()  → 读取条款文档, 返回 List<Document>
			//   new TokenTextSplitter().transform(...)     → 按 token 切块 (避免超模型上限)
			//   vectorStore.write(...)                     → 向量化 + 存库 (内部调 EmbeddingModel)
			vectorStore.write(new TokenTextSplitter().transform(new TextReader(termsOfServiceDocs).read()));

			// 相似性搜索检测
			// Note 5: ★ 自检——用 "Cancelling Bookings" 做一次相似度搜索, 验证索引建好了。
			// 打印命中文档, 启动时能在日志看到, 确认 RAG 数据可用。
			vectorStore.similaritySearch("Cancelling Bookings").forEach(doc -> {
				logger.info("Similar Document: {}", doc.getText());
			});
		};
	}

	/**
	 * 提供基于内存的向量存储（SimpleVectorStore）
	 * <p>
	 * 依赖 EmbeddingModel（自动注入，Alibaba的嵌入模型）
	 * @param embeddingModel
	 * @return
	 */
	// Note 6: ★ VectorStore Bean——RAG 检索的存储。
	// SimpleVectorStore 是内存版 (重启丢失, 生产换 Milvus/PGVector)。
	// 依赖 EmbeddingModel (DashScope 自动注入), 它负责把文本转向量。
	// 这个 Bean 会被 QuestionAnswerAdvisor 用 (RAG 检索) + CommandLineRunner 用 (灌数据)。
	@Bean
	public VectorStore vectorStore(EmbeddingModel embeddingModel) {

		return SimpleVectorStore.builder(embeddingModel).build();
	}

	/**
	 * 存储多轮对话历史（基于内存）
	 * 实现上下文感知的连续对话
	 * @return
	 */
	// Note 7: ★ ChatMemory Bean——多轮对话记忆。
	// MessageWindowChatMemory 是滑动窗口实现 (保留最近 N 条, 防 token 超限)。
	// 这个 Bean 会被 PromptChatMemoryAdvisor 用 (before 取历史 / after 存本轮)。
	// 内存版, 重启丢失 (生产换 MysqlChatMemoryRepository 等持久化, 第4站学过)。
	@Bean
	public ChatMemory chatMemory() {
		return MessageWindowChatMemory.builder().build();
	}

	/**
	 * 提供可自定义的HTTP客户端（用于调用外部API）
	 * @return
	 */
	// Note 8: RestClient.Builder Bean——HTTP 客户端 (给 ChatModel 调 LLM API 用)。
	// @ConditionalOnMissingBean: 用户没自定义时才用这个默认的, 避免覆盖用户配置。
	@Bean
	@ConditionalOnMissingBean
	public RestClient.Builder restClientBuilder() {
		return RestClient.builder();
	}
}
