/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.config.scheduling;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.cloud.ai.graph.scheduling.DefaultScheduledAgentManager;
import com.alibaba.cloud.ai.graph.scheduling.ScheduledAgentManager;
import com.alibaba.cloud.ai.graph.scheduling.ScheduledAgentTask;
import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

/**
 * ============================================
 * XXL-JOB 定时 Agent 管理器
 * ============================================
 *
 * 【核心作用】
 * 实现 ScheduledAgentManager 接口，将 Agent 的定时任务注册到 XXL-JOB 执行器中。
 * 这是 Spring AI Alibaba 定时 Agent 框架与 XXL-JOB 的"适配器"。
 *
 * 【工作原理】
 * 1. 当 Agent 调用 schedule() 时，框架会调用 registerTask()
 * 2. 本类将 Agent 任务包装成 XXL-JOB 的 IJobHandler
 * 3. 通过 XxlJobExecutor.registJobHandler() 注册到 XXL-JOB
 * 4. 当 XXL-JOB 调度中心触发调度时，执行对应的 IJobHandler
 * 5. IJobHandler 内部调用 task.execute() 执行 Agent Graph
 *
 * 【关键设计：XxlJobContext 传递】
 * 在 XXL-JOB 调度时，XxlJobContext 包含分片参数、任务参数等上下文信息。
 * 这些信息被传递给 Agent Graph，供 Agent 在执行时使用。
 * 例如：DailyReportAgent 从 XxlJobContext 中获取 access_token 用于发送钉钉消息。
 *
 * 【与 DefaultScheduledAgentManager 的区别】
 * DefaultScheduledAgentManager 使用 Spring 的 TaskScheduler（内存调度），
 * 而 XxlJobScheduledAgentManager 使用 XXL-JOB（分布式调度）。
 * 分布式调度的优势：支持集群部署、任务分片、失败重试、监控告警。
 *
 * @author yaohui
 * @create 2025/9/15 11:33
 **/
public class XxlJobScheduledAgentManager implements ScheduledAgentManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultScheduledAgentManager.class);

    /**
     * 任务调度器（用于立即执行等场景，XXL-JOB 场景下使用较少）
     */
    private static final TaskScheduler taskScheduler = new ConcurrentTaskScheduler();

    /**
     * 活跃任务列表，key 为任务名称，value 为任务对象
     */
    private final Map<String, ScheduledAgentTask> activeTasks = new ConcurrentHashMap<>();

    private volatile boolean shutdown = false;

    /**
     * 注册定时任务到 XXL-JOB
     *
     * @param task 包含 Agent 执行逻辑和配置的任务对象
     * @return 任务 ID（即任务名称）
     */
    @Override
    public String registerTask(ScheduledAgentTask task) {
        // 将 Agent 任务包装成 XXL-JOB 的 IJobHandler
        XxlJobExecutor.registJobHandler(task.getName(), new IJobHandler() {
            @Override
            public void execute() throws Exception {
                // 获取 XXL-JOB 的上下文（包含分片参数、任务参数等）
                XxlJobContext context = XxlJobContext.getXxlJobContext();

                // 将上下文传递给 Agent Graph
                Map<String, Object> inputs = Map.of("xxl-job-context", context);

                // 执行 Agent Graph
                task.execute(null, inputs);
            }
        });
        activeTasks.put(task.getName(), task);
        return task.getName();
    }

    @Override
    public boolean unregisterTask(String taskId) {
        activeTasks.remove(taskId);
        return true;
    }

    @Override
    public Optional<ScheduledAgentTask> getTask(String taskId) {
        return Optional.ofNullable(activeTasks.get(taskId));
    }

    @Override
    public Set<String> getAllActiveTaskIds() {
        return Set.of();
    }

    @Override
    public int getActiveTaskCount() {
        return 0;
    }

    @Override
    public TaskScheduler getTaskScheduler() {
        return taskScheduler;
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public void shutdown() {
        activeTasks.clear();
    }
}