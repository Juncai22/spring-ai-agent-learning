package com.pi.coding.extension;

/**
 * CLI 标志位定义 —— 用于 registerFlag() 注册命令行标志位。
 *
 * <p>定义了一个可以通过命令行参数传递给 Agent 的标志位。
 * 支持两种类型：
 * <ul>
 *   <li>{@link FlagType#BOOLEAN}：布尔类型标志位，如 {@code --verbose}</li>
 *   <li>{@link FlagType#STRING}：字符串类型标志位，如 {@code --config=path/to/config}</li>
 * </ul>
 *
 * @param name         标志位名称（不含前导短横线）
 * @param description  标志位描述（可为 null）
 * @param type         标志位类型（BOOLEAN 或 STRING）
 * @param defaultValue 默认值（可为 null）
 */
public record FlagDefinition(
    String name,
    String description,
    FlagType type,
    Object defaultValue
) {

    /**
     * 标志位类型枚举。
     */
    public enum FlagType {
        /** 布尔类型标志位，不带值，存在即为 true */
        BOOLEAN,
        /** 字符串类型标志位，需要提供值 */
        STRING
    }

    /**
     * FlagDefinition 的构建器。
     */
    public static class Builder {
        private String name;
        private String description;
        private FlagType type = FlagType.BOOLEAN;
        private Object defaultValue;

        public Builder name(String name) { this.name = name; return this; }

        public Builder description(String description) { this.description = description; return this; }

        public Builder type(FlagType type) { this.type = type; return this; }

        public Builder defaultValue(Object defaultValue) { this.defaultValue = defaultValue; return this; }

        public FlagDefinition build() {
            return new FlagDefinition(name, description, type, defaultValue);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
