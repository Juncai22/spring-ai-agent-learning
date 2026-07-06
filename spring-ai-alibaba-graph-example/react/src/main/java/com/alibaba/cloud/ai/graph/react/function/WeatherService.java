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

// Note 1: WeatherService 是 ReactAgent 的「查天气工具」。
// 它实现 java.util.function.Function<Request, Response>, 所以能被框架当 Tool 用。
//
// 这个类演示了「真实工具」的完整写法:
//   - 构造时配置 HTTP 客户端 (调外部 API 用)
//   - apply() 是工具主逻辑
//   - 内部定义 Request/Response 两个 record (LLM 据此填参数/读结果)
//   - 有真实调用 + mock 两条路径 (本示例默认走 mock, 避免依赖外部 API key)
//
// 和第 1 站 FileReadTool 对比:
//   FileReadTool: implements Tool<I,O> (项目自定义接口, BiFunction)
//   WeatherService: implements Function<I,O> (Java 标准接口)
// 两者都能当 Tool, 区别是 WeatherService 没有 ToolContext 参数。
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * @author yingzi
 * @since 2025/3/27:11:07
 */
// Note 2: implements Function<Request, Response> —— 这就是它能当 Tool 的关键。
// Request/Response 是本类内部定义的 record (下面会看到)。
// 框架 (FunctionToolCallback) 拿到这个 Function 后, LLM 决策时就调 apply(request)。
public class WeatherService implements Function<WeatherService.Request, WeatherService.Response> {

    private static final Logger logger = LoggerFactory.getLogger(WeatherService.class);

    // Note 3: 真实天气 API 的地址。weatherapi.com 提供的 forecast 接口。
    // 本示例默认走 mock 数据, 这个 URL 主要在 doGetWeather() 真实调用时用。
    private static final String WEATHER_API_URL = "https://api.weatherapi.com/v1/forecast.json";

    // Note 4: WebClient 是 Spring 的响应式 HTTP 客户端 (非阻塞)。
    // 和 RestClient (阻塞) 对应: WebClient 适合流式/异步, RestClient 适合同步。
    // 这里用它调天气 API。
    private final WebClient webClient;

    // Note 5: ObjectMapper 用于解析 API 返回的 JSON。Jackson 库的核心类。
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Note 6: 构造时建 WebClient, 把 API key 设进默认请求头。
    // 这样后续每次请求都自动带上 key, 不用重复写。
    // 注意: properties.getApiKey() 从 yml 读, 如果没配会是 null。
    public WeatherService(WeatherProperties properties) {
        this.webClient = WebClient.builder()
            // Content-Type 设为表单格式 (天气 API 要求)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
            // key 放在请求头, 天气 API 据此鉴权
            .defaultHeader("key", properties.getApiKey())
            .build();
    }

    // Note 7: 静态工厂方法: 把 API 返回的原始 JSON Map 转成 Response record。
    // 这一步是「数据清洗」——API 返回的结构复杂, 抽取出我们关心的字段 (城市名、当前天气、预报)。
    @SuppressWarnings("unchecked")
    public static Response fromJson(Map<String, Object> json) {
        Map<String, Object> location = (Map<String, Object>) json.get("location");
        Map<String, Object> current = (Map<String, Object>) json.get("current");
        Map<String, Object> forecast = (Map<String, Object>) json.get("forecast");
        List<Map<String, Object>> forecastDays = (List<Map<String, Object>>) forecast.get("forecastday");
        String city = (String) location.get("name");
        return new Response(city, current, forecastDays);
    }

    // Note 8: ★ Function 接口的核心方法, 工具的真正入口。
    // LLM 决定「调 getWeather」时, 框架把 LLM 传的参数反序列化成 Request, 调这个方法。
    @Override
    public Response apply(Request request) {
        // Note 9: 防御性校验: 请求或城市名为空时直接返回 null。
        // 返回 null 会让 LLM 收到「工具无结果」, 它会自己判断怎么办 (重试或告知用户)。
        if (request == null || !StringUtils.hasText(request.city())) {
            logger.error("Invalid request: city is required.");
            return null;
        }
        // Note 10: 中文城市名转拼音 (杭州 → hangzhou), 因为 API 不认中文。
        String location = WeatherUtils.preprocessLocation(request.city());
        // Note 11: 拼接请求 URL。UriComponentsBuilder 安全地构造 URL + 查询参数。
        // q=城市名, days=预报天数。最终 URL 类似:
        //   https://api.weatherapi.com/v1/forecast.json?q=hangzhou&days=3
        String url = UriComponentsBuilder.fromHttpUrl(WEATHER_API_URL)
            .queryParam("q", location)
            .queryParam("days", request.days())
            .toUriString();
        logger.info("url : {}", url);
        try {
            // Note 12: ★ 本示例默认走 mock (假数据), 不真实调 API。
            // 这样不用配 API key 也能跑起来。doGetWeather 是真实调用版本 (下面有)。
            return doGetWeatherMock(request);
        }
        catch (Exception e) {
            logger.error("Failed to fetch weather data: {}", e.getMessage());
            return null;
        }
    }

    // Note 13: mock 版本——根据城市名返回硬编码的假数据。
    // 杭州/上海/南京 有专门数据, 其他城市统一返回「下雪 -20 度」(搞笑的默认值)。
    // 这是开发/演示用的, 避免依赖外部 API。
    @NotNull
    private Response doGetWeatherMock(Request request) throws JsonProcessingException {
        if (Objects.equals("杭州", request.city())) {
            return new Response(request.city(), Map.of("temp", 25, "condition", "Sunny"),
                    List.of(Map.of("date", "2025-05-27", "high", 28, "low", 20)));
        }
        else if (Objects.equals("上海", request.city())) {
            return new Response(request.city(), Map.of("temp", 26, "condition", "Sunny"),
                    List.of(Map.of("date", "2025-05-27", "high", 29, "low", 21)));
        }
        else if (Objects.equals("南京", request.city())) {
            return new Response(request.city(), Map.of("temp", 18, "condition", "cloudy"),
                    List.of(Map.of("date", "2025-05-27", "high", 18, "low", 10)));
        }
        else {
            return new Response(request.city(), Map.of("temp", -20, "condition", "Snowy"),
                    List.of(Map.of("date", "2025-05-27", "high", -10, "low", -30)));
        }
    }

    // Note 14: 真实调用版本——用 WebClient 调天气 API, 拿到 JSON 后用 fromJson 解析。
    // 注意 .block() 会阻塞 (因为 WebClient 是响应式的, 这里转成同步)。
    // 本示例没用到这个方法 (apply 走的 mock), 但保留了真实实现供参考。
    @NotNull
    private Response doGetWeather(String url, Request request) throws JsonProcessingException {
        Mono<String> responseMono = webClient.get().uri(url).retrieve().bodyToMono(String.class);
        // block() 阻塞等待响应, 把异步 Mono 转成同步 String。
        String jsonResponse = responseMono.block();
        assert jsonResponse != null;

        // 把 JSON 字符串解析成 Map, 再用 fromJson 转成 Response record。
        Response response = fromJson(objectMapper.readValue(jsonResponse, new TypeReference<Map<String, Object>>() {
        }));
        logger.info("Weather data fetched successfully for city: {}", response.city());
        return response;
    }

    // Note 15: ★ Request record —— 工具的入参 schema。
    // LLM 看到的描述由这些注解决定:
    //   @JsonClassDescription  整体描述
    //   @JsonProperty          字段 JSON 名 + 是否必需
    //   @JsonPropertyDescription 字段描述 (LLM 据此理解传什么值)
    // 两个字段:
    //   city  城市名
    //   days  预报天数 (1-14)
    // Spring AI 反射这个 record 生成 JSON Schema 发给 LLM, LLM 据此填参数。
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Weather Service API request")
    public record Request(
            @JsonProperty(required = true, value = "city") @JsonPropertyDescription("city name") String city,

            @JsonProperty(required = true,
                    value = "days") @JsonPropertyDescription("Number of days of weather forecast. Value ranges from 1 to 14") int days) {
    }

    // Note 16: ★ Response record —— 工具的返回结构。
    // 注意: 这个结构主要给人/代码看, LLM 实际收到的是它的序列化形式 (JSON/toString)。
    // 三个字段: city (城市名), current (当前天气 Map), forecastDays (预报列表)。
    // LLM 拿到这个 Response 后, 会基于它生成自然语言回答给用户。
    @JsonClassDescription("Weather Service API response")
    public record Response(
            @JsonProperty(required = true, value = "city") @JsonPropertyDescription("city name") String city,
            @JsonProperty(required = true,
                    value = "current") @JsonPropertyDescription("Current weather info") Map<String, Object> current,
            @JsonProperty(required = true,
                    value = "forecastDays") @JsonPropertyDescription("Forecast weather info") List<Map<String, Object>> forecastDays) {
    }

}
