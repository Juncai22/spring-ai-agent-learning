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

package com.cloud.alibaba.ai.example.agent.model;

import java.util.List;

/**
 *
 * @author wangjx
 * @since 2026-02-13
 * */
// Note 1: EmailInfo 是 SendEmailTool 的入参结构。
// 三个字段: to (收件人列表), subject (主题), body (正文)。
// LLM 发邮件时, 从用户自然语言提取收件人、生成主题和正文填进来。
public class EmailInfo {
    /**
     * 收件人邮箱地址列表
     */
    private List<String> to;
    
    /**
     * 邮件主题
     */
    private String subject;
    
    /**
     * 邮件正文内容
     */
    private String body;

    public List<String> getTo() {
        return to;
    }

    public void setTo(List<String> to) {
        this.to = to;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
