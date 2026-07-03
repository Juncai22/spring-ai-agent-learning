package com.cloud.alibaba.ai.example;

// Note 1: Spring Boot 启动类。整个 react-agent-example 应用的入口。
// 虽然代码就这几行, 但它干了几件关键的事:
//   1. @SpringBootApplication 触发「自动装配」——扫描 starter, 自动创建 ChatModel、ReactAgent 等 Bean
//   2. SpringApplication.run() 启动内嵌 Tomcat, 暴露 AgentController 的 HTTP 接口
//   3. 组件扫描默认从本类所在包 (com.cloud.alibaba.ai.example) 开始, 所以 controller/config/tools 都能被扫到
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Note 2: @SpringBootApplication 是三个注解的合体:
//   @SpringBootConfiguration  标记这是配置类 (可含 @Bean 方法)
//   @EnableAutoConfiguration  启用自动装配 (根据 classpath 上的 starter 自动建 Bean)
//   @ComponentScan            组件扫描 (自动发现 @Controller/@Configuration/@Component 等)
// 三合一让你「写一个类就能跑起整个应用」, 这是 Spring Boot 的核心便利。
@SpringBootApplication
public class Application {

    // Note 3: main 方法是 JVM 入口。SpringApplication.run 做的事:
    //   1. 创建 Spring 容器
    //   2. 执行自动装配 (注入 ChatModel、构建 ReactAgent Bean)
    //   3. 启动内嵌 Tomcat (默认 8080 端口)
    //   4. 应用就绪, 开始接收 HTTP 请求
    // args 是命令行参数, 可传 --server.port=9090 之类覆盖默认配置。
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
