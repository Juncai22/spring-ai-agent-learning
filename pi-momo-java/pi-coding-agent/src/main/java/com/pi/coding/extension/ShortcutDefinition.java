package com.pi.coding.extension;

import java.util.concurrent.CompletableFuture;

/**
 * 快捷键定义 —— 用于 registerShortcut() 注册键盘快捷键。
 *
 * <p>定义了一个用户可以通过键盘组合键触发的快捷操作。
 * 快捷键键名格式如 "ctrl+k"、"f1"、"alt+shift+a" 等。
 *
 * @param key         键标识符（如 "ctrl+k"、"f1"）
 * @param description 快捷键描述（可为 null）
 * @param handler     快捷键执行处理器
 */
public record ShortcutDefinition(
    String key,
    String description,
    ShortcutHandler handler
) {

    /**
     * 快捷键执行的函数式接口。
     */
    @FunctionalInterface
    public interface ShortcutHandler {
        /**
         * 处理快捷键触发。
         *
         * @param context 扩展上下文
         * @return 一个 CompletableFuture，在快捷键处理完成后完成
         */
        CompletableFuture<Void> handle(ExtensionContext context);
    }

    /**
     * ShortcutDefinition 的构建器。
     */
    public static class Builder {
        private String key;
        private String description;
        private ShortcutHandler handler;

        public Builder key(String key) { this.key = key; return this; }

        public Builder description(String description) { this.description = description; return this; }

        public Builder handler(ShortcutHandler handler) { this.handler = handler; return this; }

        public ShortcutDefinition build() {
            return new ShortcutDefinition(key, description, handler);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
