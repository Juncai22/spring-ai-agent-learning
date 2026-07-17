/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.config.scheduling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;

/**
 * ============================================
 * 会话文件读取器
 * ============================================
 *
 * 【核心作用】
 * 从 classpath 下的文本文件中读取会话日志，按分隔符切分为独立的会话记录。
 *
 * 【使用场景】
 * 用于从文件导入测试数据，模拟用户对话记录进行分析。
 * 分隔符为 "============================================"。
 *
 * 【注意】
 * 当前代码中这个类未被直接使用（EvaluationAgent 使用数据库查询而非文件读取），
 * 但保留了作为备用数据加载方式的选项。
 */
public class SessionFileReader {

    /**
     * 读取会话文件，按分隔符 "============================================" 切分
     *
     * @param filePath classpath 下的文件路径，如 "sessions.txt"
     * @return 每个元素为一条完整会话记录
     * @throws IOException 文件读取异常
     */
    public static List<String> readSessionsFromFile(String filePath) throws IOException {
        List<String> sessions = new ArrayList<>();
        ClassPathResource resource = new ClassPathResource(filePath);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream()))) {
            StringBuilder currentSession = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("============================================")) {
                    // 遇到分隔符 → 保存当前会话，开始新会话
                    if (currentSession.length() > 0) {
                        String session = currentSession.toString().trim();
                        if (!session.isEmpty()) {
                            sessions.add(session);
                        }
                        currentSession = new StringBuilder();
                    }
                } else {
                    currentSession.append(line).append("\n");
                }
            }

            // 处理最后一个会话（文件末尾没有分隔符）
            if (currentSession.length() > 0) {
                String session = currentSession.toString().trim();
                if (!session.isEmpty()) {
                    sessions.add(session);
                }
            }
        }
        return sessions;
    }
}