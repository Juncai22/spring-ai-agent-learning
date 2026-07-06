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
package com.cloud.alibaba.ai.example.agent.rag.tool;

// Note 1: ★★ KnowledgeRetrievalTool 是本模块的核心——把「向量检索」包成 Tool 给 Agent 用。
//
// ★ 核心认知: RAG 在 Agent 体系里不是独立范式, 而是 ReAct 的一个工具!
//   传统 RAG: 固定流程 (问题 → 检索 → 生成), 每次都先检索
//   Agentic RAG (本例): Agent 自主决定要不要检索、检索什么、检索几次
//
// 对比第5站 tool-calling: 那里 WeatherService 是查天气工具
//                         这里 KnowledgeRetrievalTool 是查知识库工具
//                         本质完全一样——都是 BiFunction + FunctionToolCallback
//
// implements BiFunction<Request, ToolContext, String>: 项目自定义 Tool 接口模式 (第6站学过)
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Knowledge Retrieval Tool for RAG Agent
 * <p>
 * This tool enables the agent to retrieve relevant documents from a knowledge base
 * using vector similarity search. It follows the Agentic RAG pattern where the
 * agent decides when and how to use the retrieval tool.
 * </p>
 *
 * @author zth9
 * @since 2026-01-22
 */
// Note 2: @Component 让 Spring 扫描注册为 Bean, 可注入到 RagAgentConfiguration。
@Component
public class KnowledgeRetrievalTool implements BiFunction<KnowledgeRetrievalTool.Request, ToolContext, String> {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeRetrievalTool.class);

    // Note 3: 默认返回 top 4 条最相关文档。可被 Request.topK 覆盖。
    private static final int DEFAULT_TOP_K = 4;

    // Note 4: ★ SimpleVectorStore——Spring AI 的简易内存向量库。
    // 生产环境换 Milvus/PGVector/Redis 等持久化向量库。
    // 它存的是文档的向量表示 (embedding), 支持相似度搜索。
    private final SimpleVectorStore vectorStore;

    // Note 5: 知识源 URL 列表, 从 yml 的 rag.knowledge.sources 读取。
    // 启动时 initKnowledgeBase() 会从这些 URL 抓文档建索引。
    @Value("${rag.knowledge.sources}")
    private List<String> knowledgeSourceUrls;

    // Note 6: 构造时建向量库。EmbeddingModel 把文本转向量 (DashScope 提供)。
    public KnowledgeRetrievalTool(EmbeddingModel embeddingModel) {
        this.vectorStore = SimpleVectorStore.builder(embeddingModel).build();
    }

    // Note 7: ★★★ @PostConstruct——Bean 初始化后自动调, 建知识库索引。
    // 这就是 RAG 的「数据准备」阶段: 抓文档 → 切块 → 向量化 → 存库。
    @PostConstruct
    void initKnowledgeBase() {
        logger.info("Initializing knowledge base from {} sources...", knowledgeSourceUrls.size());

        for (String url : knowledgeSourceUrls) {
            try {
                // ① 抓文档: JsoupDocumentReader 从 URL 抓网页内容, 转成 Document
                JsoupDocumentReader reader = new JsoupDocumentReader(url);
                List<Document> documents = reader.get();
                logger.info("Loaded {} documents from {}", documents.size(), url);

                // ② 切块: TokenTextSplitter 按 token 数把长文档切成小块
                // 为什么切块: 向量检索按块匹配, 太长的文档匹配不准 + 超 token 上限
                TokenTextSplitter splitter = new TokenTextSplitter();
                List<Document> splitDocuments = splitter.apply(documents);
                logger.info("Split into {} chunks", splitDocuments.size());

                // ③ 向量化 + 存库: add() 内部用 EmbeddingModel 把每块转成向量, 存进 SimpleVectorStore
                vectorStore.add(splitDocuments);
                logger.info("Added {} chunks to vector store", splitDocuments.size());
            }
            catch (Exception e) {
                logger.warn("Failed to load documents from {}: {}", url, e.getMessage());
            }
        }

        logger.info("Knowledge base initialization completed");
    }

    // Note 8: ★★ 工具主逻辑——Agent 决定调 knowledge_retrieval 时, 框架调这个方法。
    // 这是 RAG 的「检索」阶段: 接收 query → 向量相似度搜索 → 返回相关文档。
    @Override
    public String apply(Request request, ToolContext toolContext) {
        logger.info("========== Knowledge Retrieval Tool Start ==========");
        logger.info("Query: {}", request.query());

        int topK = request.topK() != null ? request.topK() : DEFAULT_TOP_K;

        // Note 9: ★ 向量相似度搜索——把 query 转向量, 找最相似的 topK 个文档块。
        // SearchRequest.builder().query(query).topK(topK).build()
        // vectorStore.similaritySearch 内部: query → embedding → 余弦相似度比对 → 返回 topK
        SearchRequest searchRequest = SearchRequest.builder().query(request.query()).topK(topK).build();

        List<Document> documents = vectorStore.similaritySearch(searchRequest);

        // Note 10: 没找到相关文档——返回提示让 LLM 自己判断 (不抛异常)。
        if (documents.isEmpty()) {
            logger.info("No relevant documents found for query: {}", request.query());
            return "No relevant information found in the knowledge base for the given query.";
        }

        // Note 11: ★ 格式化检索结果给 LLM。
        // 每个文档用 --- 分隔, LLM 据此综合生成回答。
        // 这就是 RAG 的「增强」——把检索到的知识塞进 LLM 的上下文。
        String result = documents.stream()
            .map(doc -> "---\n" + doc.getFormattedContent() + "\n---")
            .collect(Collectors.joining("\n\n"));

        logger.info("Retrieved {} relevant documents", documents.size());
        logger.info("========== Knowledge Retrieval Tool End ==========");

        return result;
    }

    // Note 12: ★ 工具元数据——名字 "knowledge_retrieval" + 详细 description。
    // description 决定 Agent 何时调它: "answer questions about Spring AI Alibaba..."
    // Agent 看到用户问 Spring AI 相关问题, 就知道该调这个工具。
    public ToolCallback toolCallback() {
        return FunctionToolCallback.builder("knowledge_retrieval", this)
            .description("Retrieves relevant information from the Spring AI Alibaba knowledge base. "
                    + "Use this tool when you need to answer questions about Spring AI Alibaba, "
                    + "its features, configuration, or usage. " + "The tool performs semantic search to find the most "
                    + "relevant documentation based on the query.")
            .inputType(Request.class)
            .build();
    }

    // Note 13: Request record——入参 schema。LLM 据此填 query 和 topK。
    // query: 搜索关键词 (必填)
    // topK: 返回几条 (可选, 默认4)
    @JsonClassDescription("Request for knowledge retrieval from the documentation")
    public record Request(@JsonProperty(value = "query", required = true)
    @JsonPropertyDescription("The search query to find relevant documentation. "
            + "Be specific and include key terms related to your question.") String query,

            @JsonProperty(value = "top_k")
            @JsonPropertyDescription("Number of top results to retrieve (default: 4)") Integer topK) {
    }

}
