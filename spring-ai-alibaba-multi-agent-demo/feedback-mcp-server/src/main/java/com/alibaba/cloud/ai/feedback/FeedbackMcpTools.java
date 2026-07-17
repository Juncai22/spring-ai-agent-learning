/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.feedback;

import com.alibaba.cloud.ai.feedback.entity.Feedback;
import com.alibaba.cloud.ai.feedback.service.FeedbackService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ============================================
 * 反馈 MCP 工具类
 * ============================================
 *
 * 【核心作用】
 * 通过 MCP 协议对外暴露反馈相关的 4 个工具，供 FeedbackAgent 远程调用。
 *
 * 【工具清单】
 * ┌──────────────────────────────────────────────────────┐
 * │ ① feedback-create-feedback        创建反馈记录       │
 * │ ② feedback-get-feedback-by-user   按用户ID查反馈     │
 * │ ③ feedback-get-feedback-by-order  按订单ID查反馈     │
 * │ ④ feedback-update-solution        更新解决方案       │
 * └──────────────────────────────────────────────────────┘
 *
 * 【反馈类型】
 * 1-产品反馈（口味、质量等）
 * 2-服务反馈（配送、态度等）
 * 3-投诉（需紧急处理）
 * 4-建议（改进意见）
 *
 * 【与 OrderMcpTools 的对比】
 * | 维度       | OrderMcpTools (9个工具) | FeedbackMcpTools (4个工具) |
 * |-----------|------------------------|---------------------------|
 * | 复杂度     | 高（下单/查询/修改/删除） | 低（反馈记录 CRUD）        |
 * | 安全设计   | 双重验证(用户ID+订单ID)  | 简单验证(用户ID/订单ID)    |
 * | 数据转换   | 甜度/冰量自然语言→数字   | 反馈类型数字→文本          |
 * | 共同点     | 都是 MCP Server，都返回 String，都注册到 Nacos   |
 */
@Service
public class FeedbackMcpTools {

    @Autowired
    private FeedbackService feedbackService;

    /**
     * 创建反馈记录
     *
     * 支持 4 种反馈类型：
     * 1-产品反馈, 2-服务反馈, 3-投诉, 4-建议
     *
     * @param userId       用户ID（必填）
     * @param feedbackType 反馈类型（1-4）
     * @param content      反馈内容
     * @param orderId      关联订单ID（可选）
     * @param rating       评分 1-5 星（可选）
     */
    @Tool(name = "feedback-create-feedback",
          description = "创建用户反馈记录，userId是必填项")
    public String createFeedback(
            @ToolParam(description = "用户ID，必填") Long userId,
            @ToolParam(description = "反馈类型：1-产品反馈，2-服务反馈，3-投诉，4-建议") Integer feedbackType,
            @ToolParam(description = "反馈内容") String content,
            @ToolParam(description = "关联订单ID，可选") String orderId,
            @ToolParam(description = "评分1-5星，可选") Integer rating) {

        try {
            Feedback feedback = new Feedback();
            feedback.setUserId(userId);
            feedback.setFeedbackType(feedbackType);
            feedback.setContent(content);
            if (orderId != null && !orderId.trim().isEmpty()) {
                feedback.setOrderId(orderId);
            }
            if (rating != null) {
                feedback.setRating(rating);
            }

            Feedback createdFeedback = feedbackService.createFeedback(feedback);
            return String.format("反馈记录创建成功！反馈ID: %d, 用户ID: %d, 反馈类型: %s, 内容: %s",
                    createdFeedback.getId(),
                    createdFeedback.getUserId(),
                    createdFeedback.getFeedbackTypeText(),
                    createdFeedback.getContent());
        } catch (Exception e) {
            return "创建反馈记录失败: " + e.getMessage();
        }
    }

    /**
     * 按用户ID查询反馈
     * 用于 Agent 在处理用户反馈前，先了解该用户的历史反馈记录
     */
    @Tool(name = "feedback-get-feedback-by-user",
          description = "根据用户ID查询反馈记录")
    public String getFeedbacksByUserId(
            @ToolParam(description = "用户ID") Long userId) {
        try {
            List<Feedback> feedbacks = feedbackService.getFeedbacksByUserId(userId);
            if (feedbacks.isEmpty()) {
                return "该用户暂无反馈记录";
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("用户 %d 的反馈记录（共 %d 条）：\n", userId, feedbacks.size()));

            for (Feedback feedback : feedbacks) {
                result.append(String.format(
                        "- 反馈ID: %d, 类型: %s, 评分: %s, 内容: %s, 时间: %s\n",
                        feedback.getId(),
                        feedback.getFeedbackTypeText(),
                        feedback.getRatingText(),
                        feedback.getContent(),
                        feedback.getCreatedAt()));
            }
            return result.toString();
        } catch (Exception e) {
            return "查询用户反馈记录失败: " + e.getMessage();
        }
    }

    /**
     * 按订单ID查询反馈
     * 用于 Agent 在处理订单相关投诉时，查找对应的反馈记录
     */
    @Tool(name = "feedback-get-feedback-by-order",
          description = "根据订单ID查询反馈记录")
    public String getFeedbacksByOrderId(
            @ToolParam(description = "订单ID") String orderId) {
        try {
            List<Feedback> feedbacks = feedbackService.getFeedbacksByOrderId(orderId);
            if (feedbacks.isEmpty()) {
                return "该订单暂无反馈记录";
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("订单 %s 的反馈记录（共 %d 条）：\n", orderId, feedbacks.size()));

            for (Feedback feedback : feedbacks) {
                result.append(String.format(
                        "- 反馈ID: %d, 用户ID: %d, 类型: %s, 评分: %s, 内容: %s, 时间: %s\n",
                        feedback.getId(),
                        feedback.getUserId(),
                        feedback.getFeedbackTypeText(),
                        feedback.getRatingText(),
                        feedback.getContent(),
                        feedback.getCreatedAt()));
            }
            return result.toString();
        } catch (Exception e) {
            return "查询订单反馈记录失败: " + e.getMessage();
        }
    }

    /**
     * 更新反馈解决方案
     * 用于 Agent 在处理投诉后，记录解决方案（如退款、补偿券等）
     */
    @Tool(name = "feedback-update-solution",
          description = "更新反馈解决方案")
    public String updateFeedbackSolution(
            @ToolParam(description = "反馈ID") Long feedbackId,
            @ToolParam(description = "解决方案") String solution) {
        try {
            boolean success = feedbackService.updateFeedbackSolution(feedbackId, solution);
            return success
                    ? String.format("反馈ID %d 的解决方案更新成功：%s", feedbackId, solution)
                    : String.format("反馈ID %d 的解决方案更新失败", feedbackId);
        } catch (Exception e) {
            return "更新反馈解决方案失败: " + e.getMessage();
        }
    }

    /**
     * 反馈类型数字 → 文本
     * 1→产品反馈, 2→服务反馈, 3→投诉, 4→建议
     */
    private String getFeedbackTypeText(Integer feedbackType) {
        if (feedbackType == null) return "未知";
        switch (feedbackType) {
            case 1: return "产品反馈";
            case 2: return "服务反馈";
            case 3: return "投诉";
            case 4: return "建议";
            default: return "未知";
        }
    }
}