package com.pi.coding.tool;

import com.pi.ai.core.types.CancellationSignal;

import java.util.concurrent.CompletableFuture;

/**
 * Bash 操作接口：定义执行 Shell 命令的统一契约。
 *
 * <p>该接口采用策略模式设计，允许不同的实现方式来执行 Bash 命令：
 * <ul>
 *   <li>本地执行：通过 {@link DefaultBashOperations} 使用 Java {@link ProcessBuilder} 直接执行</li>
 *   <li>远程执行：可通过实现该接口将命令发送到远程服务器执行</li>
 *   <li>沙箱化执行：可在容器或隔离环境中执行命令</li>
 * </ul>
 *
 * <p>所有方法均返回 {@link CompletableFuture}，支持异步执行和取消操作。
 */
public interface BashOperations {

    /**
     * 执行一条 Bash 命令。
     * <p>
     * 该方法异步执行命令，并在完成后返回包含 stdout、stderr 和退出码的结果。
     * 支持可选的超时设置和取消信号。
     *
     * @param command 要执行的 Bash 命令字符串
     * @param timeout 超时时间（秒），如果为 null 则无超时限制
     * @param signal 取消信号，用于在命令执行过程中取消操作
     * @return 包含执行结果的 CompletableFuture
     */
    CompletableFuture<BashResult> execute(String command, Integer timeout, CancellationSignal signal);

    /**
     * Bash 命令执行的结果记录。
     * <p>
     * 封装了命令执行后的三个关键输出：
     * <ul>
     *   <li>stdout - 标准输出流的内容</li>
     *   <li>stderr - 标准错误流的内容</li>
     *   <li>exitCode - 进程退出码（0 表示成功）</li>
     * </ul>
     *
     * @param stdout 标准输出内容
     * @param stderr 标准错误输出内容
     * @param exitCode 进程退出码
     */
    record BashResult(
        String stdout,
        String stderr,
        int exitCode
    ) {
        /**
         * 合并标准输出和标准错误输出。
         * <p>
         * 如果 stdout 和 stderr 均不为空，则用换行符拼接两者。
         * 如果其中一个为空，则仅返回非空的那个。
         * 如果两者均为空，则返回空字符串。
         *
         * @return 合并后的完整输出字符串
         */
        public String combinedOutput() {
            StringBuilder sb = new StringBuilder();
            if (stdout != null && !stdout.isEmpty()) {
                sb.append(stdout);
            }
            if (stderr != null && !stderr.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(stderr);
            }
            return sb.toString();
        }
    }
}
