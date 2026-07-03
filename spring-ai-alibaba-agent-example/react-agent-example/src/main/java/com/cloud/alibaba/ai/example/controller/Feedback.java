package com.cloud.alibaba.ai.example.controller;

// Note 1: Feedback 是「用户审批反馈」的数据载体, 用 Java 14+ 的 record 声明 (不可变值对象)。
//
// 它对应 AgentController 的 /feedback 接口的请求体 (Request body)。
// 前端把用户对每个工具调用的审批决定, 封装成 Feedback 列表 POST 过来。
//
// record 的好处: 一行声明 + 自动生成构造器/getter/equals/hashCode/toString, 极简洁。
// 适合这种「纯数据传输」场景, 即 DTO (Data Transfer Object)。
//
// 两个字段:
//   isApproved  是否批准 (true=同意执行该工具, false=拒绝)
//   feedback    反馈内容 (拒绝时填原因, 会传回给 LLM 让它知道为啥被拒)
public record Feedback(boolean isApproved, String feedback) {
}
