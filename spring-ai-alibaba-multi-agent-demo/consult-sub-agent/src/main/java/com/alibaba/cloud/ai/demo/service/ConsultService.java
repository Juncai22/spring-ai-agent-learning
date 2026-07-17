/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.service;

import com.alibaba.cloud.ai.demo.entity.Product;
import com.alibaba.cloud.ai.demo.mapper.ProductMapper;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ============================================
 * 咨询服务类
 * ============================================
 *
 * 【核心作用】
 * 为咨询 Agent 提供两种数据来源的检索服务：
 * 1. RAG 知识库检索（阿里云百炼 DashScope）
 * 2. MySQL 数据库查询（产品信息）
 *
 * 【RAG 检索流程】
 * 用户查询 → DashScopeApi.retriever()
 *   ├── Embedding 向量化
 *   ├── 知识库语义检索
 *   ├── Rerank 重排序（可选）
 *   └── 返回 Top-N 文档
 *
 * 【知识库内容】
 * 位于 consult-sub-agent/src/main/resources/knowledge/：
 * - brand-overview.md：品牌概览和理念
 * - products.md：产品详细介绍
 * 这些文件需要先上传到阿里云百炼知识库，获取 index-id。
 *
 * 【重排序（Reranking）】
 * enable-reranking: true 时，检索后会使用重排序模型对结果重新打分。
 * 过滤掉 rerank-min-score 以下的文档，只保留 rerank-top-n 个结果。
 * 这能显著提高检索精度，避免无关文档干扰 LLM 的回答。
 */
@Service
public class ConsultService {

    private static final Logger logger = LoggerFactory.getLogger(ConsultService.class);

    /** 百炼知识库 ID */
    @Value("${spring.ai.dashscope.document-retrieval.index-id}")
    private String indexID;

    /** 是否启用重排序 */
    @Value("${spring.ai.dashscope.document-retrieval.enable-reranking}")
    private boolean enableReranking;

    /** 重排序后保留的文档数 */
    @Value("${spring.ai.dashscope.document-retrieval.rerank-top-n}")
    private int rerankTopN;

    /** 重排序的最低分数阈值 */
    @Value("${spring.ai.dashscope.document-retrieval.rerank-min-score}")
    private float rerankMinScore;

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    private DashScopeApi dashscopeApi;

    @Autowired
    private ProductMapper productMapper;

    public ConsultService() {}

    /**
     * 初始化 DashScope API 客户端
     * @PostConstruct 确保在依赖注入完成后执行
     */
    @PostConstruct
    public void initRetriever() {
        this.dashscopeApi = DashScopeApi.builder().apiKey(apiKey).build();
    }

    /**
     * ============================================
     * RAG 知识库检索
     * ============================================
     *
     * @param query 用户查询文本
     * @return 检索到的文档内容（合并为一个字符串）
     */
    public String searchKnowledge(String query) {
        logger.info("=== ConsultService.searchKnowledge 入口 ===");
        logger.info("请求参数 - query: {}", query);

        try {
            // 构建检索选项
            DashScopeDocumentRetrieverOptions options = DashScopeDocumentRetrieverOptions.builder()
                    .withEnableReranking(enableReranking)       // 启用重排序
                    .withRerankTopN(rerankTopN)                 // 返回 Top-N 文档
                    .withRerankMinScore(rerankMinScore)         // 最低分数过滤
                    .build();

            // 调用百炼知识库检索 API
            List<Document> documents = dashscopeApi.retriever(indexID, query, options);
            logger.info("检索到文档数量: {}", documents.size());

            if (documents.isEmpty()) {
                return "未找到相关资料，查询内容：" + query;
            }

            // 合并所有文档内容
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < documents.size(); i++) {
                Document document = documents.get(i);
                String text = document.getText();
                if (!text.trim().isEmpty()) {
                    result.append(text);
                    if (i < documents.size() - 1) {
                        result.append("\n\n");  // 文档间用双换行分隔
                    }
                }
            }

            String finalResult = result.toString();
            logger.info("返回结果长度: {} 字符", finalResult.length());
            return finalResult;
        } catch (Exception e) {
            logger.error("知识库检索异常", e);
            return "知识库检索失败: " + e.getMessage() + "，查询内容：" + query;
        }
    }

    /** 获取所有可用产品列表 */
    public List<Product> getAllProducts() {
        try {
            return productMapper.selectAllAvailable();
        } catch (Exception e) {
            logger.error("获取产品列表异常", e);
            return new ArrayList<>();
        }
    }

    /** 根据产品名称获取产品详情 */
    public Product getProductByName(String productName) {
        try {
            return productMapper.selectByNameAndStatus(productName, 1);
        } catch (Exception e) {
            logger.error("获取产品详情异常", e);
            return null;
        }
    }

    /** 模糊搜索产品 */
    public List<Product> searchProductsByName(String productName) {
        try {
            return productMapper.selectByNameLike(productName);
        } catch (Exception e) {
            logger.error("搜索产品异常", e);
            return new ArrayList<>();
        }
    }

    /** 验证产品是否存在且可用 */
    public boolean validateProduct(String productName) {
        try {
            return productMapper.existsByNameAndStatusTrue(productName) > 0;
        } catch (Exception e) {
            logger.error("验证产品异常", e);
            return false;
        }
    }
}