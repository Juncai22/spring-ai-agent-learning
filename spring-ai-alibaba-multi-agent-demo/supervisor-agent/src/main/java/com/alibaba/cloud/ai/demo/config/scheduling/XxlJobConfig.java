/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.config.scheduling;

import java.util.List;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.scheduling.ScheduleConfig;
import com.alibaba.cloud.ai.graph.scheduling.ScheduleLifecycleListener;
import com.alibaba.cloud.ai.graph.scheduling.ScheduledAgentManagerFactory;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ============================================
 * XXL-JOB 定时任务调度配置
 * ============================================
 *
 * 【核心作用】
 * 集成 XXL-JOB 分布式任务调度框架，为 Agent 的定时执行提供调度能力。
 *
 * 【什么是 XXL-JOB】
 * XXL-JOB 是一个轻量级分布式任务调度框架，核心功能：
 * - 调度中心（Admin）：管理定时任务、触发调度
 * - 执行器（Executor）：接收调度指令并执行任务
 * 本项目只使用执行器部分，将 Agent 的定时任务注册到 XXL-JOB 执行器中。
 *
 * 【条件启用】
 * @ConditionalOnProperty(prefix = "xxl.job", name = "enabled", havingValue = "true")
 * 只有当 xxl.job.enabled=true 时才加载此配置。
 * 默认情况下 xxl.job.enabled=false，定时任务功能不启用。
 *
 * 【Agent 定时执行流程】
 * 1. 管理员通过 AdminAgent 说"每天8点执行经营日报"
 * 2. CronTaskParseAgent 调用 CronAgentTools.createCronAgent()
 * 3. 创建 ScheduledAgentTask 并注册到 XxlJobScheduledAgentManager
 * 4. XXL-JOB 调度中心按 cron 表达式触发调度
 * 5. XxlJobScheduledAgentManager 执行对应的 Agent Graph
 * 6. Agent 完成数据分析和报告生成，通过钉钉发送结果
 *
 * 【initAgentTask 方法】
 * 演示用途：为所有 CompiledGraph Bean 自动注册一个默认的定时任务（每天8点执行）。
 * 实际生产环境中，定时任务应通过管理端动态创建，而非启动时自动注册。
 *
 * @author xuxueli 2017-04-28
 */
@Configuration
@ConditionalOnProperty(prefix = "xxl.job", name = "enabled", havingValue = "true", matchIfMissing = false)
public class XxlJobConfig {

    private Logger logger = LoggerFactory.getLogger(XxlJobConfig.class);

    @Value("${xxl.job.admin.addresses}")        private String adminAddresses;
    @Value("${xxl.job.accessToken}")             private String accessToken;
    @Value("${xxl.job.executor.appname}")        private String appname;
    @Value("${xxl.job.executor.address:}")        private String address;
    @Value("${xxl.job.executor.ip:}")             private String ip;
    @Value("${xxl.job.executor.port:}")           private int port;
    @Value("${xxl.job.executor.logpath}")         private String logPath;
    @Value("${xxl.job.executor.logretentiondays}") private int logRetentionDays;

    /**
     * 创建 XXL-JOB 执行器 Bean
     *
     * @param agents Spring 容器中所有 CompiledGraph 类型的 Bean（自动注入）
     *               这些 Agent 将被自动注册为可定时执行的任务
     * @return XXL-JOB 执行器
     */
    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(@Autowired(required = false) List<CompiledGraph> agents) {
        logger.info(">>>>>>>>>>> xxl-job config init.");

        // 创建 XXL-JOB 执行器
        XxlJobSpringExecutor xxlJobSpringExecutor = new XxlJobSpringExecutor();
        xxlJobSpringExecutor.setAdminAddresses(adminAddresses);
        xxlJobSpringExecutor.setAppname(appname);
        xxlJobSpringExecutor.setAddress(address);
        xxlJobSpringExecutor.setIp(ip);
        xxlJobSpringExecutor.setPort(port);
        xxlJobSpringExecutor.setAccessToken(accessToken);
        xxlJobSpringExecutor.setLogPath(logPath);
        xxlJobSpringExecutor.setLogRetentionDays(logRetentionDays);

        // 注册自定义的 ScheduledAgentManager 工厂
        // 这样 Agent 的 schedule() 方法会使用 XXL-JOB 作为调度后端
        ScheduledAgentManagerFactory.getInstance()
                .registerProvider(XxlJobScheduledAgentManager::new);

        // 为所有 Agent 自动注册默认的定时任务（演示用）
        initAgentTask(agents);

        return xxlJobSpringExecutor;
    }

    /**
     * 演示：为所有 Agent 自动注册默认的定时任务
     *
     * 每个 Agent 都会被注册为每天 8:00 执行。
     * 实际生产环境中，这应该通过管理端界面动态配置而非硬编码。
     *
     * @param agents 所有可用的 CompiledGraph
     */
    private void initAgentTask(List<CompiledGraph> agents) {
        for (CompiledGraph agent : agents) {
            ScheduleConfig config = ScheduleConfig.builder()
                    .cronExpression("0 0 8 * * ?")    // 每天 8:00 执行
                    .addListener(new ScheduleLifecycleListener() {
                        @Override
                        public void onEvent(ScheduleEvent event, Object data) {
                            if (event == ScheduleEvent.EXECUTION_COMPLETED) {
                                if (data instanceof OverAllState state) {
                                    System.out.println(">>>>>>>>>>> xxl-job agent task completed: " + state);
                                }
                            }
                        }
                    })
                    .build();
            agent.schedule(config);
        }
    }
}