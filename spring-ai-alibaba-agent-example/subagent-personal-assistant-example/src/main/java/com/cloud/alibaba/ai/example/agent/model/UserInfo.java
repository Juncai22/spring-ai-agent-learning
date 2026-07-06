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

/**
 *
 * @author wangjx
 * @since 2026-02-13
 * */
// Note 1: UserInfo 是 UserDataTool 的入参/返回结构。
// 三个字段: userName (用户名), email (邮箱), departmentName (部门)。
// UserDataTool 用它查询用户信息——可按用户名或部门筛选。
// 这个类会被 Spring AI 反射生成 JSON Schema, LLM 据此填参数。
public class UserInfo {
    /**
     * 用户名称
     */
    private String userName;

    /**
     * 用户邮箱
     */
    private String email;

    /**
     * 部门名称
     */
    private String departmentName;

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDepartmentName() {
        return departmentName;
    }

    public void setDepartmentName(String departmentName) {
        this.departmentName = departmentName;
    }
}
