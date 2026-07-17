package com.pi.coding.tool;

import java.util.Map;

/**
 * Bash 命令生成上下文记录：封装命令执行所需的全部环境信息。
 *
 * <p>该记录用于 {@link BashSpawnHook} 机制，允许在命令执行前通过钩子
 * 修改执行上下文。上下文包含三个核心要素：
 * <ul>
 *   <li>command - 要执行的命令字符串</li>
 *   <li>cwd - 当前工作目录</li>
 *   <li>env - 环境变量映射</li>
 * </ul>
 *
 * <p>提供三个 {@code withXxx} 方法，分别用于修改命令、工作目录和环境变量，
 * 每个方法返回一个新的记录实例，遵循不可变设计模式。
 *
 * @param command 要执行的命令字符串
 * @param cwd 当前工作目录路径
 * @param env 环境变量键值对映射
 */
public record BashSpawnContext(
    String command,
    String cwd,
    Map<String, String> env
) {
    /**
     * 创建一个新的上下文，仅替换命令字符串，保留原工作目录和环境变量。
     *
     * @param newCommand 新的命令字符串
     * @return 修改后的新上下文实例
     */
    public BashSpawnContext withCommand(String newCommand) {
        return new BashSpawnContext(newCommand, cwd, env);
    }

    /**
     * 创建一个新的上下文，仅替换工作目录，保留原命令和环境变量。
     *
     * @param newCwd 新的工作目录路径
     * @return 修改后的新上下文实例
     */
    public BashSpawnContext withCwd(String newCwd) {
        return new BashSpawnContext(command, newCwd, env);
    }

    /**
     * 创建一个新的上下文，仅替换环境变量映射，保留原命令和工作目录。
     *
     * @param newEnv 新的环境变量映射
     * @return 修改后的新上下文实例
     */
    public BashSpawnContext withEnv(Map<String, String> newEnv) {
        return new BashSpawnContext(command, cwd, newEnv);
    }
}
