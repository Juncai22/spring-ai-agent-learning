/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.example.chat.dashscope.network;

// Note 1: 本类是「生产级网络配置」示例，解决高并发调用大模型时的网络层问题。
// 默认的 HTTP 客户端配置偏保守，生产环境常需自定义: 连接池大小、超时、连接复用、空闲清理。
//
// 关键认知: Spring AI 的同步调用 (call) 用 RestClient，流式调用 (stream) 用 WebClient。
// 两者底层都可替换:
//   WebClient (响应式) -> 底层默认 Reactor Netty，可换成自定义 Netty HttpClient。
//   RestClient (阻塞)  -> 底层可换 JDK HttpClient / OkHttp / Apache HttpClient 等。
// 通过 DashScopeApi.builder().webClientBuilder() / .restClientBuilder() 注入。
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeApiSpec;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

import jakarta.servlet.http.HttpServletResponse;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 *
 * 演示自定义 httpClient 以解决请求模型过程中的网络问题
 *
 * 对于 Stream，将 WebClient 的底层引擎替换为 NettyHttpClient，并优化 Netty 资源池和连接复用
 * 对于 Call，将 RestClient 替换为 OkHttpClient/JDK HttpClient。合理设置 ConnectionPool 和 ReadTimeOut
 */

@RestController
@RequestMapping("/cfg")
public class NetworkConfigDemo {

    private final ChatClient dashScopeChatClient;

    // Note 2: 无参构造器里手动 build 整条链: ChatModel -> ChatClient。
    // 因为没有用构造器注入 ChatModel，所以 @Autowired 不会触发——这是一个完全自给自足的示例。
    public NetworkConfigDemo() {

        this.dashScopeChatClient = ChatClient.builder(getDashScopeChatModel())
                .defaultAdvisors(new SimpleLoggerAdvisor()).build();
    }

    @GetMapping("/call")
    public String testCall() {

        return dashScopeChatClient.prompt("hi").call().content();
    }

    @GetMapping("/stream")
    public Flux<String> testStream(HttpServletResponse response) {

        response.setCharacterEncoding("UTF-8");
        return dashScopeChatClient.prompt("hi").stream().content();
    }

    // Note 3: 组装 DashScopeChatModel，把自定义的网络客户端通过 getDashscopeAPI() 注入。
    // 同时配置了默认选项 (模型 + 联网搜索)，与 NetworkConfigDemo 的网络优化是正交的两件事。
    private DashScopeChatModel getDashScopeChatModel() {

        return DashScopeChatModel.builder()
                .dashScopeApi(getDashscopeAPI()).defaultOptions(
                        DashScopeChatOptions.builder()
                                .model("qwen-plus")
                                .enableSearch(true)
                                .searchOptions(DashScopeApiSpec.SearchOptions.builder()
                                        .enableSource(true)
                                        .forcedSearch(true)
                                        .searchStrategy("turbo")
                                        .build()
                                ).build()
                ).build();
    }

    private static DashScopeApi getDashscopeAPI() {

        // 配置HTTP连接池
        // Note 4: ConnectionProvider 是 Reactor Netty 的连接池抽象。默认连接池较小且无生命周期管理，
        // 高并发下会出现连接耗尽或泄漏。这里精细配置:
        //   maxConnections: 池上限 500，支撑高并发。
        //   maxIdleTime:    空闲连接保留 10 分钟，超过则回收，避免长期占用。
        //   maxLifeTime:    连接最长存活 30 分钟，强制刷新，防止长连接老化 (DNS 变更、对端重启等)。
        //   evictInBackground: 后台每 60 秒清理一次过期连接，无需请求触发，降低延迟抖动。
        ConnectionProvider provider = ConnectionProvider.builder("dashscope")
                .maxConnections(500)
                .maxIdleTime(Duration.ofMinutes(10))  // 空闲连接保持10分钟
                .maxLifeTime(Duration.ofMinutes(30))  // 连接最大生命周期30分钟
                .evictInBackground(Duration.ofSeconds(60))  // 每60秒清理一次过期连接
                .build();

        // 配置HTTP客户端
        // Note 5: 在连接池之上再配置超时——这是生产环境防雪崩的关键:
        //   CONNECT_TIMEOUT_MILLIS: TCP 建连超时 10s，避免对端不可达时长时间卡住。
        //   responseTimeout: 整体响应超时 60s，大模型生成慢，给足时间但不能无限等。
        //   ReadTimeoutHandler:  读超时 60s (Netty pipeline 层)，针对两次数据间间隔。
        //   WriteTimeoutHandler: 写超时 10s，请求体一般很小，写太久说明网络异常。
        // 四个超时各管一段，组合起来才能精准定位故障，而非一刀切。
        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)  // 连接超时10秒
                .responseTimeout(Duration.ofSeconds(60))  // 响应超时60秒
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(60))  // 读超时60秒
                        .addHandlerLast(new WriteTimeoutHandler(10))  // 写超时10秒
                );

        // 构建WebClient实例
        // Note 6: 把自定义 Netty HttpClient 通过 ReactorClientHttpConnector 接入 WebClient。
        // 此 WebClient 专用于 stream() 流式调用 (响应式，基于 Netty)。
        WebClient.Builder webClientbuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        // 可选配置
        // 添加请求日志记录功能
        //.filter(ExchangeFilterFunction.ofRequestProcessor(
        //        clientRequest -> {
        //            log.debug("Request: {} {}",
        //                    clientRequest.method(),
        //                    clientRequest.url());
        //            return Mono.just(clientRequest);
        //        }
        //))
        // 添加响应日志记录功能
        //.filter(ExchangeFilterFunction.ofResponseProcessor(
        //        clientResponse -> {
        //            log.debug("Response status: {}",
        //                    clientResponse.statusCode());
        //            return Mono.just(clientResponse);
        //        }
        //));

        // Note 7: 同时注入 webClientBuilder (管 stream) 和 restClientBuilder (管 call)。
        // RestClient 用 JdkClientHttpRequestFactory，底层是 JDK 自带的 HttpClient (Java 11+)，
        // 无需额外依赖，适合阻塞式同步调用。也可换成 OkHttp/Apache HttpClient。
        // 这种「响应式与阻塞双客户端」配置，让 call 与 stream 各走各的优化路径。
        return DashScopeApi.builder()
                .apiKey("sk-xxx")
                .webClientBuilder(webClientbuilder)
                .restClientBuilder(RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()))
                .build();
    }

}
