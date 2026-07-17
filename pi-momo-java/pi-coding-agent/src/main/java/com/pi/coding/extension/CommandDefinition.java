package com.pi.coding.extension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 命令定义 —— 用于 registerCommand() 注册斜杠命令。
 *
 * <p>定义了一个用户可通过 "/" 前缀触发的斜杠命令。命令可以：
 * <ul>
 *   <li>有唯一名称和描述</li>
 *   <li>支持参数自动补全</li>
 *   <li>通过异步处理器执行命令逻辑</li>
 *   <li>接收 {@link ExtensionCommandContext} 执行高级会话操作</li>
 * </ul>
 *
 * @param name                    命令名称（不含前导斜杠）
 * @param description             命令描述
 * @param getArgumentCompletions  参数自动补全函数，接收当前参数文本返回补全项（可为 null）
 * @param handler                 命令执行处理器
 */
public record CommandDefinition(
    String name,
    String description,
    Function<String, List<AutocompleteItem>> getArgumentCompletions,
    CommandHandler handler
) {

    /**
     * 命令执行的函数式接口。
     */
    @FunctionalInterface
    public interface CommandHandler {
        /**
         * 处理命令执行。
         *
         * @param args    命令参数字符串
         * @param context 扩展命令上下文，提供会话控制方法
         * @return 一个 CompletableFuture，在命令执行完成后完成
         */
        CompletableFuture<Void> handle(String args, ExtensionCommandContext context);
    }

    /**
     * CommandDefinition 的构建器。
     */
    public static class Builder {
        private String name;
        private String description;
        private Function<String, List<AutocompleteItem>> getArgumentCompletions;
        private CommandHandler handler;

        public Builder name(String name) { this.name = name; return this; }

        public Builder description(String description) { this.description = description; return this; }

        public Builder getArgumentCompletions(Function<String, List<AutocompleteItem>> getArgumentCompletions) {
            this.getArgumentCompletions = getArgumentCompletions;
            return this;
        }

        public Builder handler(CommandHandler handler) { this.handler = handler; return this; }

        public CommandDefinition build() {
            return new CommandDefinition(name, description, getArgumentCompletions, handler);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
