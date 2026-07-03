package com.cloud.alibaba.ai.example.interceptor;

// Note 1: LogToolInterceptor 是「工具调用拦截器」——每次工具被调用时, 它都能拦下来记日志。
//
// 和你之前学的 Advisor (拦截 LLM 请求/响应) 不同:
//   Advisor         拦截「LLM 调用」层面 (prompt → response)
//   ToolInterceptor 拦截「工具调用」层面 (LLM 决定调工具 → 工具真正执行)
//
// 典型用途:
//   - 日志记录 (本例): 记录哪个工具被调了
//   - 审计: 敏感工具调用留痕
//   - 限流: 防止 LLM 短时间调太多次工具
//   - 改写参数: 调用前修改 LLM 传的参数
//
// 工作位置 (在 Agent 流程中):
//   LLM 决策 → "我要调 file_read" → [ToolInterceptor 拦截] → 真正执行 file_read.apply()
//                                          ↑ 这里记日志
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


// Note 2: 继承 ToolInterceptor (抽象类), 重写两个方法。
// 这是「模板方法模式」: 框架定义好拦截流程, 你只填具体逻辑。
public class LogToolInterceptor extends ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LogToolInterceptor.class);

    // Note 3: ★ 核心方法: interceptToolCall。
    // 参数:
    //   request  LLM 要调用的工具信息 (工具名、参数等)
    //   handler  「放行器」——调用它才会真正执行工具
    // 这种 (request, handler) 的签名是典型拦截器写法, 类似 Spring MVC 的 HandlerInterceptor。
    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        // Note 4: 拦截点 ①: 工具执行「前」——记录工具名。
        // request.getToolName() 拿到 LLM 决定调用的工具名 (如 "file_read")。
        log.info("ToolInterceptor: Tool {} is called!", request.getToolName());
        // Note 5: ★ 关键: 调用 handler.call(request) 才会真正执行工具。
        // 如果不调这一行, 工具就被「拦截」了 (可用于禁用某些工具)。
        // 调用后返回的 ToolCallResponse 含工具执行结果, 把它原样返回给上层。
        // 也可以在这里 (handler.call 之后) 再加日志, 记录执行结果——这是拦截点 ②。
        return handler.call(request);
    }

    // Note 6: 拦截器的名字, 用于框架内部识别和日志。
    // 当多个拦截器串联时, 名字方便区分是谁拦的。
    @Override
    public String getName() {
        return "LogToolInterceptor";
    }
}
