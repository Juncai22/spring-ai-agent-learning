/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.tools;

import com.alibaba.cloud.ai.demo.entity.Product;
import com.alibaba.cloud.ai.demo.service.ConsultService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ============================================
 * 咨询本地工具类
 * ============================================
 *
 * 【核心作用】
 * 提供咨询 Agent 的本地工具方法，全部使用 @Tool 注解注册。
 * 这些工具会被 MethodToolCallbackProvider 自动发现并注入到 ReactAgent 中。
 *
 * 【工具分类】
 * ┌────────────────────────────────────────────────────┐
 * │  RAG 检索工具                                       │
 * │  searchKnowledge() → 调用阿里云百炼知识库检索       │
 * ├────────────────────────────────────────────────────┤
 * │  数据库查询工具                                     │
 * │  getProducts()      → 获取所有产品列表              │
 * │  getProductInfo()   → 获取单个产品详细信息          │
 * │  searchProducts()   → 模糊搜索产品                  │
 * └────────────────────────────────────────────────────┘
 *
 * 【本地工具 vs MCP 远程工具】
 * 本地工具（本类）：直接调用本地 Service，适合轻量级操作
 * MCP 远程工具（memory-mcp-server）：通过网络调用，适合需要独立部署的服务
 *
 * 为什么产品查询不放在 MCP Server 中？
 * 因为产品数据在 MySQL 中，consult-sub-agent 已经配置了数据源，
 * 直接本地查询更简单高效，不需要额外的网络开销。
 *
 * 为什么记忆管理放在 MCP Server 中？
 * 因为 Mem0 是外部服务，多个子 Agent 都需要使用，
 * 放在 MCP Server 中可以统一管理和复用。
 *
 * @see ConsultService 业务服务层（RAG 检索 + 数据库查询）
 */
@Service
public class ConsultTools {

    @Autowired
    private ConsultService consultService;

    /**
     * ============================================
     * RAG 知识库检索工具
     * ============================================
     *
     * 调用阿里云百炼（DashScope）知识库进行语义检索。
     * 支持重排序（reranking），提高检索精度。
     *
     * 【RAG 工作流程】
     * 用户问题 → Embedding 向量化 → 知识库检索 → Rerank 重排序 → 返回 Top-N 文档
     *
     * 这是你模块 13（rag-example）学过的传统 RAG 模式，
     * 但这里把检索包装成了 Agent 的工具，Agent 自主决定何时检索。
     * 这就是模块 14（rag-agent-example）的 Agentic RAG 模式。
     */
    @Tool(name = "consult-search-knowledge",
          description = "根据用户查询内容检索云边奶茶铺知识库，包括产品信息、店铺介绍等。支持模糊匹配，可以查询产品名称、描述、分类、茶底等信息。")
    public String searchKnowledge(
            @ToolParam(description = "查询内容，可以是产品名称、产品描述关键词、店铺信息关键词等，例如：云边茉莉、经典奶茶、品牌介绍等")
            String query) {
        try {
            String result = consultService.searchKnowledge(query);
            return result;
        } catch (Exception e) {
            return "知识库检索失败: " + e.getMessage();
        }
    }

    /**
     * 获取所有可用产品列表
     * 从 MySQL 数据库查询所有状态为上架的产品
     */
    @Tool(name = "consult-get-products",
          description = "获取云边奶茶铺所有可用产品的完整列表，包括产品名称、详细描述、当前价格和库存数量。帮助用户了解可选择的奶茶产品。")
    public String getProducts() {
        try {
            List<Product> products = consultService.getAllProducts();
            if (products.isEmpty()) {
                return "当前没有任何可用产品。";
            }

            StringBuilder result = new StringBuilder("云边奶茶铺可用产品列表:\n");
            for (Product product : products) {
                result.append(String.format("- %s: %s, 价格: %.2f元, 库存: %d件\n",
                        product.getName(), product.getDescription(),
                        product.getPrice(), product.getStock()));
            }
            return result.toString();
        } catch (Exception e) {
            return "获取产品列表失败: " + e.getMessage();
        }
    }

    /**
     * 获取单个产品详细信息
     * 包含名称、描述、价格、库存、保质期、制作时间
     */
    @Tool(name = "consult-get-product-info",
          description = "获取指定产品的详细信息，包括产品描述、价格和当前库存状态。帮助用户了解产品的具体信息。")
    public String getProductInfo(
            @ToolParam(description = "产品名称，必须是云边奶茶铺的现有产品，如：云边茉莉、桂花云露、云雾观音、云山红韵、云桃乌龙、云边普洱、云桂龙井、云峰山茶")
            String productName) {
        try {
            Product product = consultService.getProductByName(productName);
            if (product == null) {
                return "产品不存在或已下架: " + productName;
            }
            return String.format(
                    "产品信息:\n名称: %s\n描述: %s\n价格: %.2f元\n库存: %d件\n保质期: %d分钟\n制作时间: %d分钟",
                    product.getName(), product.getDescription(), product.getPrice(),
                    product.getStock(), product.getShelfTime(), product.getPreparationTime());
        } catch (Exception e) {
            return "获取产品信息失败: " + e.getMessage();
        }
    }

    /**
     * 模糊搜索产品
     * 支持部分名称匹配，如搜索"云"可以找到所有含"云"字的产品
     */
    @Tool(name = "consult-search-products",
          description = "根据产品名称进行模糊搜索，返回匹配的产品列表。支持部分名称搜索，例如搜索'云'可以找到所有包含'云'字的产品。")
    public String searchProducts(
            @ToolParam(description = "产品名称关键词，支持模糊匹配，例如：云、茉莉、乌龙等")
            String productName) {
        try {
            List<Product> products = consultService.searchProductsByName(productName);
            if (products.isEmpty()) {
                return "未找到匹配的产品: " + productName;
            }
            StringBuilder result = new StringBuilder("搜索结果 (" + products.size() + " 个产品):\n");
            for (Product product : products) {
                result.append(String.format("- %s: %s, 价格: %.2f元, 库存: %d件\n",
                        product.getName(), product.getDescription(),
                        product.getPrice(), product.getStock()));
            }
            return result.toString();
        } catch (Exception e) {
            return "搜索产品失败: " + e.getMessage();
        }
    }
}