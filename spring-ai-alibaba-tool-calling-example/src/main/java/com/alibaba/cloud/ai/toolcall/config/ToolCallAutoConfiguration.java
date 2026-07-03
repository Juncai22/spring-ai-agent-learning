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
package com.alibaba.cloud.ai.toolcall.config;

// Note 1: 本类是「集中式 Bean 配置」—— 把所有 Tools 类的依赖装配放在一个地方。
//
// 这种写法的优势:
//   1. 统一管理: 所有 Tools 类的实例化、依赖注入都在这里, Controller 只管 @Autowired 即可。
//   2. 易于切换: 换 Tools 实现只改这里, Controller 不动。
//   3. 条件加载: @ConditionalOnClass 让配置「缺依赖时不报错」。
//
// 对比在每个 Controller 里 new Tools 类的写法:
//   本类写法:    @Autowired 注入,  全局共享
//   分散写法:    new Tools(),     每个 Controller 持有自己实例 (重复)
//   推荐:        ★ 集中配置, Controller 纯使用。
import com.alibaba.cloud.ai.toolcall.component.AddressInformationTools;
import com.alibaba.cloud.ai.toolcall.component.CampusScheduleTools;
import com.alibaba.cloud.ai.toolcall.component.TimeTools;
import com.alibaba.cloud.ai.toolcalling.baidumap.BaiduMapSearchInfoService;
import com.alibaba.cloud.ai.toolcalling.time.GetTimeByZoneIdService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Note 2: @ConditionalOnClass 是个防御性条件——只有当 classpath 上有 GetTimeByZoneIdService
// 这个类时, 整个 @Configuration 才生效。目的是「依赖缺失时不启动应用」。
// 原理: starter-time 提供这个类, 没引 starter-time 就不会有这个 Bean, 配置类整体失效。
@ConditionalOnClass(GetTimeByZoneIdService.class)
@Configuration
public class ToolCallAutoConfiguration {

    // Note 3: Spring AI Alibaba 把外部 API 客户端 (百度地图、百度翻译、天气、时间) 做成独立的 starter,
    // 启动时自动注入这些 Service Bean。这里手动 new 出来作为 Bean 注册——演示自定义配置覆盖默认。
    // 一般生产环境会用框架的自动配置, 这里显式 new 是为了「演示完整流程」。
    @Bean
    public GetTimeByZoneIdService getCurrentTimeByTimeZoneIdService() {
        return new GetTimeByZoneIdService();
    }

    // Note 4: TimeTools 依赖 GetTimeByZoneIdService, Spring 自动按类型注入。
    // 这种「Service 干活, Tools 包装」的拆分, 让每个 Bean 职责单一。
    @Bean
    public TimeTools timeTools(GetTimeByZoneIdService service) {
        return new TimeTools(service);
    }

    @Bean
    public CampusScheduleTools campusScheduleTools() {
        return new CampusScheduleTools();
    }

    // Note 5: AddressInformationTools 依赖 BaiduMapSearchInfoService (也是 starter-baidumap 提供)。
    // 注意: 百度地图需要 yml 配置 apiKey, 否则 Service 内部调用会失败。
    @Bean
    public AddressInformationTools addressInformationTools(BaiduMapSearchInfoService service) {
        return new AddressInformationTools(service);
    }

    // Note 6: 全局共享的 ChatClient Bean。所有 Controller 注入的是同一个实例。
    // 挂上 SimpleLoggerAdvisor 让所有请求都自动打印日志 (开发期必备)。
    // 与你之前在 dashscope-chat 里手动 new ChatClient.builder() 不同的写法:
    //   之前: 每个 Controller 构造时建一个 ChatClient
    //   现在: 整个应用共享一个 ChatClient (单例)
    // 单例更省内存, 也保证配置一致——所有接口看到同一个「默认配置」。
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

}
