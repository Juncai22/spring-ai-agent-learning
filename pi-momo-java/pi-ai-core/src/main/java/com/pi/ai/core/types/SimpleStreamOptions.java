package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 扩展的流式调用选项，增加推理/思考相关参数。
 *
 * <p>继承 {@link StreamOptions} 的所有字段，额外包含：
 * <ul>
 *   <li>{@code reasoning} — 思考级别（{@link ThinkingLevel}）</li>
 *   <li>{@code thinkingBudgets} — 自定义思考 token 预算（{@link ThinkingBudgets}）</li>
 * </ul>
 *
 * <p>使用 {@link #simpleBuilder()} 创建 Builder 实例：
 * <pre>{@code
 * SimpleStreamOptions opts = SimpleStreamOptions.simpleBuilder()
 *     .temperature(0.7)
 *     .maxTokens(4096)
 *     .reasoning(ThinkingLevel.HIGH)
 *     .thinkingBudgets(new ThinkingBudgets(null, null, null, 32768))
 *     .build();
 * }</pre>
 *
 * <p>对应 TypeScript 中的 {@code SimpleStreamOptions} 接口。
 */
// 序列化时忽略值为 null 的字段
@JsonInclude(JsonInclude.Include.NON_NULL)
// 继承 StreamOptions，增加推理/思考相关参数
public class SimpleStreamOptions extends StreamOptions {

    /** 思考/推理级别，控制模型进行深入思考的程度 */
    @JsonProperty("reasoning")
    private ThinkingLevel reasoning;

    /** 自定义思考 Token 预算，精细控制各思考级别的最大 Token 消耗 */
    @JsonProperty("thinkingBudgets")
    private ThinkingBudgets thinkingBudgets;

    /** Jackson 反序列化用默认构造器。 */
    public SimpleStreamOptions() {
    }

    // Step 1: 私有构造器，从 Builder 构建实例
    // 调用父类构造器设置继承的字段，再设置本类特有字段
    private SimpleStreamOptions(Builder builder) {
        super(builder);
        this.reasoning = builder.reasoning;
        this.thinkingBudgets = builder.thinkingBudgets;
    }

    /**
     * 子类 Builder 构造器，供子类（如 ProxyStreamOptions）继承扩展。
     *
     * @param builder 子类 Builder 实例
     * @param reasoning 思考级别
     * @param thinkingBudgets 思考 token 预算
     */
    // Step 1: 受保护的构造器，供子类（如 ProxyStreamOptions）继承使用
    // 原因：子类需要先调用父类构造器，再设置本类特有字段
    protected SimpleStreamOptions(AbstractBuilder<?> builder, ThinkingLevel reasoning, ThinkingBudgets thinkingBudgets) {
        super(builder);
        this.reasoning = reasoning;
        this.thinkingBudgets = thinkingBudgets;
    }

    // --- Getters ---

    public ThinkingLevel getReasoning() {
        return reasoning;
    }

    public ThinkingBudgets getThinkingBudgets() {
        return thinkingBudgets;
    }

    // --- equals / hashCode / toString ---

    @Override
    public boolean equals(Object o) {
        // Step 1: 自引用检查
        if (this == o) return true;
        // Step 2: 类型检查
        if (!(o instanceof SimpleStreamOptions that)) return false;
        // Step 3: 先调用父类的 equals 比较继承的字段
        if (!super.equals(o)) return false;
        // Step 4: 再比较本类特有的字段
        return reasoning == that.reasoning
            && Objects.equals(thinkingBudgets, that.thinkingBudgets);
    }

    @Override
    public int hashCode() {
        // 合并父类哈希值和本类特有字段的哈希值
        return Objects.hash(super.hashCode(), reasoning, thinkingBudgets);
    }

    @Override
    public String toString() {
        // 返回包含父类字段和本类特有字段的字符串表示
        return "SimpleStreamOptions{" +
            "reasoning=" + reasoning +
            ", thinkingBudgets=" + thinkingBudgets +
            ", " + super.toString() +
            '}';
    }

    // --- Builder ---

    /** 创建 SimpleStreamOptions 的 Builder。 */
    public static Builder simpleBuilder() {
        return new Builder();
    }

    /**
     * SimpleStreamOptions 的 Builder，继承 StreamOptions.AbstractBuilder 的所有方法。
     */
    // Step 1: Builder 继承 AbstractBuilder，实现泛型自引用
    public static final class Builder extends AbstractBuilder<Builder> {
        private ThinkingLevel reasoning;       // 思考级别（可选）
        private ThinkingBudgets thinkingBudgets; // 思考 Token 预算（可选）

        Builder() {
        }

        /** 设置思考级别 */
        public Builder reasoning(ThinkingLevel reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        /** 设置思考 Token 预算 */
        public Builder thinkingBudgets(ThinkingBudgets thinkingBudgets) {
            this.thinkingBudgets = thinkingBudgets;
            return this;
        }

        @Override
        public SimpleStreamOptions build() {
            return new SimpleStreamOptions(this);
        }
    }
}