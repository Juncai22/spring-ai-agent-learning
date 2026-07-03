package com.cloud.alibaba.ai.example.tools;

// Note 1: 本接口定义了本项目所有工具的「统一契约」。
// 任何一个想被 ReactAgent 调用的工具, 都要实现这个接口。
//
// 设计要点: 它继承自 Java 标准的 BiFunction<I, ToolContext, O>, 含义是:
//   I  = 输入类型 (通常是工具自己的 Request record)
//   O  = 输出类型 (通常是 String, 即工具返回给 LLM 的文本)
//   ToolContext = Spring AI 提供的「工具上下文」, 可携带额外信息 (如会话 ID、用户身份)
//
// 与 Spring AI 原生 FunctionToolCallback 的关系:
//   原生方式: 直接实现 Function<I, O> (单参)
//   本项目方式: 实现 BiFunction<I, ToolContext, O> (双参, 多了 ToolContext)
//   多出来的 ToolContext 让工具能拿到「调用时的上下文」, 更灵活。
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.function.BiFunction;

// Note 2: 泛型参数 <I, O>:
//   I = Input,  工具的入参类型 (每个工具自定义一个 Request record)
//   O = Output, 工具的返回类型 (一般用 String, 因为 LLM 只能理解文本)
// 继承 BiFunction 意味着: 工具本质上是一个「带上下文的函数」。
public interface Tool<I, O> extends BiFunction<I, ToolContext, O> {

    // Note 3: 唯一要实现的方法: 把自己包装成 Spring AI 的 ToolCallback。
    // ToolCallback 是 Spring AI 框架认识的「工具句柄」, ReactAgent 通过它调用工具。
    // 之所以单独抽这个方法, 是为了把「函数逻辑」和「工具元数据(名字/描述/schema)」解耦:
    //   apply()        管逻辑 (读文件/写文件)
    //   toolCallback() 管元数据 (告诉 LLM 这工具叫啥、怎么调)
    // 这样一个 Tool 既能当普通函数用, 也能暴露给 LLM 用。
    ToolCallback toolCallback();

}
