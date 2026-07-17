package com.pi.coding.tool;

/**
 * Bash 命令生成钩子接口：用于在命令执行前自定义执行上下文。
 *
 * <p>该接口是一个函数式接口（{@link FunctionalInterface}），
 * 允许以 Lambda 表达式或方法引用的方式实现。钩子机制的应用场景包括：
 * <ul>
 *   <li>注入临时环境变量（如 API 密钥、临时凭证）</li>
 *   <li>修改命令路径（如重定向到容器中的命令）</li>
 *   <li>添加安全限制（如限制可访问的目录）</li>
 *   <li>审计日志记录（记录所有执行的命令）</li>
 * </ul>
 *
 * @see BashSpawnContext 钩子操作的上下文对象
 * @see DefaultBashOperations 使用此钩子的默认实现
 */
@FunctionalInterface
public interface BashSpawnHook {

    /**
     * 在命令执行前调整生成上下文。
     * <p>
     * 此方法接收原始的 {@link BashSpawnContext}，返回修改后的上下文。
     * 可以通过 {@code withCommand}、{@code withCwd}、{@code withEnv} 方法
     * 创建修改后的上下文副本。
     *
     * @param context 原始生成上下文，包含命令、工作目录和环境变量
     * @return 修改后的生成上下文，用于后续的命令执行
     */
    BashSpawnContext adjust(BashSpawnContext context);
}
