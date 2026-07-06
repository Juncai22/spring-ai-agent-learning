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

// Note 1: 本类把 WeatherService 注册成一个 Bean, 名字叫 "getWeatherFunction"。
// 这个名字很关键——ReactAutoconfiguration 里 .defaultToolNames("getWeatherFunction")
// 就是靠这个名字找到工具并挂给 LLM 的。
//
// 这是一种「按名字引用工具」的模式:
//   工具定义在配置类里 → 注册成具名 Bean → Agent 用名字引用
// 对比第 1 站 react-agent-example: 那里是直接 new FileReadTool().toolCallback() 传实例。
// 按名字引用更解耦: Agent 不直接依赖工具类, 只认名字, 工具可独立替换。
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

// Note 2: 三个条件注解, 控制 Bean 是否生效:
//   @ConditionalOnClass(WeatherService.class)
//     classpath 上要有 WeatherService 类 (本模块肯定有, 这里主要演示用法)
//   @ConditionalOnProperty(... weather.enabled=true)
//     yml 里 spring.ai.alibaba.toolcalling.weather.enabled=true 才生效
//     这样可以通过 yml 开关控制是否启用天气工具
// 这些条件让「工具可选」——没配 API key 时整块不生效, 不会报错。
@Configuration
@ConditionalOnClass(WeatherService.class)
@ConditionalOnProperty(prefix = "spring.ai.alibaba.toolcalling.weather", name = "enabled", havingValue = "true")
public class WeatherAutoConfiguration {

    // Note 3: ★ 注册 WeatherService 为 Bean。
    // name = "getWeatherFunction"  Bean 的名字 (Agent 按这个名字找它)
    // @ConditionalOnMissingBean    如果用户没自定义同名 Bean, 才用这个默认的
    // @Description("...")          这个 Bean 的描述 (用于工具发现场景)
    //
    // 注意 Bean 名 "getWeatherFunction" 和方法名 "getWeatherServiceFunction" 不一样。
    // Spring 默认 Bean 名是方法名, 但这里用 @Bean(name=...) 显式指定了。
    // ReactAutoconfiguration 里 .defaultToolNames("getWeatherFunction") 引用的就是 name。
    @Bean(name = "getWeatherFunction")
    @ConditionalOnMissingBean
    @Description("Use api.weather to get weather information.")
    public WeatherService getWeatherServiceFunction(WeatherProperties properties) {
        return new WeatherService(properties);
    }

}
