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

package com.alibaba.cloud.ai.graph.react;

// Note 1: Spring Boot 启动类, 整个 graph-example/react 应用的入口。
// 启动时会:
//   1. 自动装配 ChatModel (DashScope/OpenAI starter 提供)
//   2. 触发 WeatherAutoConfiguration (注册 getWeatherFunction Bean, 需 yml 里 weather.enabled=true)
//   3. 触发 ReactAutoconfiguration (构建 ReactAgent + 编译 Graph + 打印 PlantUML)
//   4. 启动内嵌 Tomcat, 暴露 ReactController 的 /react/chat 接口
import com.alibaba.cloud.ai.graph.react.function.WeatherProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

// Note 2: @EnableConfigurationProperties(WeatherProperties.class)
// 显式启用 WeatherProperties 的配置绑定。
// 作用: 让 WeatherProperties 能从 yml 读 spring.ai.alibaba.toolcalling.weather.* 配置。
// 没这个注解, @ConfigurationProperties 不会生效 (除非在 META-INF 里配置)。
@SpringBootApplication
@EnableConfigurationProperties(WeatherProperties.class)
public class ReactApplication {

    // Note 3: main 方法 → SpringApplication.run 启动应用。
    // 启动后访问 GET /react/chat?query=杭州天气怎么样 即可体验 ReAct。
    // 启动时控制台会打印 PlantUML 图结构 (ReactAutoconfiguration 里 System.out.println 的),
    // 把那段文本贴到 plantuml.com 能看到 ReAct 内部长啥样。
    public static void main(String[] args) {
        SpringApplication.run(ReactApplication.class, args);
    }

}
