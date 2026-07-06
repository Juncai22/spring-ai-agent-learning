/*
 * Copyright 2025 the original author or authors.
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

package com.alibaba.cloud.ai.graph;

// Note 1: Spring Boot 启动类, parallel-node 模块入口。
// 启动时:
//   1. 自动装配 ChatClient.Builder (给 TranslateNode/ExpanderNode 用)
//   2. 触发 ParallelNodeGraphConfiguration, 构建 parallelNodeGraph Bean (★ 画好并行图)
//   3. 启动 Tomcat, 暴露 ParallelNodeGraphController 的 /graph/stream/expand 接口
//   4. 控制台打印并行图的 PlantUML 结构
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * @author sixiyida
 * @since 2025/6/27
 */

// Note 2: @SpringBootApplication = 配置 + 自动装配 + 组件扫描 三合一。
// 启动后访问: GET /graph/stream/expand?query=你好&expander_number=3
// 会触发并行图: dispatcher 同时分发 translator + expander, collector 收集后返回。
// 因为是 SSE 流式, 前端能实时看到翻译和扩展的生成过程。
@SpringBootApplication
public class ParallelNodeApplication {

    // Note 3: main 方法启动应用。SpringApplication.run 创建容器、装配 Bean、启动 Tomcat。
    public static void main(String[] args) {
        SpringApplication.run(ParallelNodeApplication.class, args);
    }
}
