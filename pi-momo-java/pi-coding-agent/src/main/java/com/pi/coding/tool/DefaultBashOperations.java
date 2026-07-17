package com.pi.coding.tool;

import com.pi.ai.core.types.CancellationSignal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 默认的 Bash 操作实现：通过本地 Java 进程执行 Shell 命令。
 *
 * <p>该实现使用 {@link ProcessBuilder} 启动本地 {@code bash -c} 进程来执行命令，
 * 并异步读取标准输出和标准错误输出。主要特性包括：
 * <ul>
 *   <li>支持命令前缀注入（如 Docker 容器执行前缀）</li>
 *   <li>支持 {@link BashSpawnHook} 钩子，可在命令执行前自定义环境</li>
 *   <li>支持超时控制和取消信号</li>
 *   <li>超时或取消时自动销毁进程树，防止僵尸进程</li>
 *   <li>异步读取 stdout 和 stderr，避免死锁</li>
 * </ul>
 */
public class DefaultBashOperations implements BashOperations {

    /** 当前工作目录，所有命令在此目录下执行 */
    private final String cwd;
    /** 进程环境变量映射 */
    private final Map<String, String> env;
    /** 命令前缀，会在每个命令前拼接（如 "docker exec container"） */
    private String commandPrefix;
    /** 生成钩子，可在命令执行前修改上下文 */
    private BashSpawnHook spawnHook;

    /**
     * 使用指定工作目录创建实例，继承系统环境变量。
     *
     * @param cwd 当前工作目录路径
     */
    public DefaultBashOperations(String cwd) {
        this(cwd, System.getenv());
    }

    /**
     * 使用指定工作目录和环境变量创建实例。
     * 适用于需要自定义环境变量（如临时修改 PATH 或设置特定环境）的场景。
     *
     * @param cwd 当前工作目录路径
     * @param env 环境变量映射
     */
    public DefaultBashOperations(String cwd, Map<String, String> env) {
        this.cwd = cwd;
        this.env = env;
    }

    /**
     * 设置命令前缀，该前缀会拼接到每个要执行的命令之前。
     * <p>
     * 常用于在容器或远程环境中执行命令，例如：
     * <ul>
     *   <li>Docker: {@code docker exec my-container}</li>
     *   <li>SSH: {@code ssh user@host}</li>
     * </ul>
     *
     * @param prefix 命令前缀字符串
     */
    public void setCommandPrefix(String prefix) {
        this.commandPrefix = prefix;
    }

    /**
     * 设置生成钩子，用于在命令执行前自定义执行上下文。
     * <p>
     * 钩子可以修改命令、工作目录或环境变量，适用于需要动态调整执行环境的场景，
     * 如注入临时凭证、修改命令路径等。
     *
     * @param hook 生成钩子实现
     */
    public void setSpawnHook(BashSpawnHook hook) {
        this.spawnHook = hook;
    }

    @Override
    public CompletableFuture<BashResult> execute(String command, Integer timeout, CancellationSignal signal) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Apply command prefix
                String finalCommand = command;
                if (commandPrefix != null && !commandPrefix.isEmpty()) {
                    finalCommand = commandPrefix + " " + command;
                }

                // Create spawn context
                BashSpawnContext context = new BashSpawnContext(finalCommand, cwd, env);

                // Apply spawn hook if set
                if (spawnHook != null) {
                    context = spawnHook.adjust(context);
                }

                // Build process
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", context.command());
                pb.directory(new java.io.File(context.cwd()));
                pb.environment().putAll(context.env());
                pb.redirectErrorStream(false);

                Process process = pb.start();

                // Read stdout and stderr
                CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        return reader.lines().collect(Collectors.joining("\n"));
                    } catch (IOException e) {
                        return "";
                    }
                });

                CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                        return reader.lines().collect(Collectors.joining("\n"));
                    } catch (IOException e) {
                        return "";
                    }
                });

                // Wait for process with optional timeout
                boolean completed;
                if (timeout != null && timeout > 0) {
                    completed = process.waitFor(timeout, TimeUnit.SECONDS);
                    if (!completed) {
                        destroyProcessTree(process);
                        throw new RuntimeException("Command timed out after " + timeout + " seconds");
                    }
                } else {
                    // Poll for completion, checking cancellation signal
                    while (process.isAlive()) {
                        if (signal != null && signal.isCancelled()) {
                            destroyProcessTree(process);
                            throw new RuntimeException("Command cancelled");
                        }
                        try {
                            completed = process.waitFor(100, TimeUnit.MILLISECONDS);
                            if (completed) break;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            destroyProcessTree(process);
                            throw new RuntimeException("Command execution interrupted", e);
                        }
                    }
                    completed = true;
                }

                String stdout = stdoutFuture.join();
                String stderr = stderrFuture.join();
                int exitCode = process.exitValue();

                return new BashResult(stdout, stderr, exitCode);

            } catch (IOException e) {
                throw new RuntimeException("Failed to execute command: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Command execution interrupted", e);
            }
        });
    }

    /**
     * 销毁进程及其整个进程树，防止产生僵尸进程。
     * <p>
     * 在超时或取消操作时调用此方法，确保：
     * <ol>
     *   <li>先销毁所有子进程（通过 {@link ProcessHandle#descendants()}）</li>
     *   <li>再销毁主进程</li>
     *   <li>等待最多 5 秒让进程优雅退出</li>
     *   <li>如果仍未退出，则强制销毁（{@link Process#destroyForcibly()}）</li>
     * </ol>
     *
     * @param process 要销毁的进程对象
     */
    private void destroyProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
