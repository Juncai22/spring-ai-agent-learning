/*
 * Copyright 2024-2025 the original author or authors.
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

package com.alibaba.cloud.ai.graph.react.function;

// Note 1: WeatherProperties 是「天气服务的配置属性类」。
// 它绑定 application.yml 里 spring.ai.alibaba.toolcalling.weather.* 这一段配置。
// 当前只有一个属性: apiKey (天气 API 的密钥)。
//
// 这是 Spring Boot 的标准配置绑定模式:
//   yml 配置  →  @ConfigurationProperties 类  →  注入到 Service 里使用
// 好处: 配置和代码解耦, 换 apiKey 不用改代码, 改 yml 即可。
import org.springframework.boot.context.properties.ConfigurationProperties;

// Note 2: prefix = "spring.ai.alibaba.toolcalling.weather"
// 表示本类的字段会从 yml 里这一前缀下读取。
// 比如 yml 里有:
//   spring:
//     ai:
//       alibaba:
//         toolcalling:
//           weather:
//             api-key: xxx
// 那 apiKey 字段就会自动被赋值为 "xxx"。
@ConfigurationProperties(prefix = "spring.ai.alibaba.toolcalling.weather")
public class WeatherProperties {

    // Note 3: 天气 API 的密钥。用 weatherapi.com 的服务需要这个 key。
    // 本示例实际用的是 mock 数据 (见 WeatherService.doGetWeatherMock), 这个 key 主要用于真实调用时。
    private String apiKey;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

}
