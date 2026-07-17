package com.pi.ai.core.types;

/**
 * Mutable token usage accumulator for streaming scenarios.
 * 流式场景下的可变 Token 用量累加器，用于在流式响应中逐步累积用量数据。
 *
 * <p>During streaming, token counts arrive incrementally. This class
 * collects them and converts to an immutable {@link Usage} record
 * via {@link #toUsage()} once the stream completes.
 * 在流式处理过程中，Token 计数是逐步到达的。本类负责收集这些增量数据，
 * 并在流结束后通过 {@link #toUsage()} 转换为不可变的 {@link Usage} record。
 */
// Step 1: 使用 mutable class 而非 record
// 原因：流式场景中 Token 计数是逐块到达的，需要可变的累加器
public final class MutableUsage {

    /** 输入 Token 累计数 */
    private int input;
    /** 输出 Token 累计数 */
    private int output;
    /** 缓存读取 Token 累计数 */
    private int cacheRead;
    /** 缓存写入 Token 累计数 */
    private int cacheWrite;
    /** 累计费用（可选，流结束后设置） */
    private Usage.Cost cost;

    // Step 2: 默认无参构造器，所有字段初始化为 0 或 null
    public MutableUsage() { }

    // --- Accumulation methods --- 累加方法

    /**
     * 累加输入 Token 数。
     *
     * @param tokens 本次流式块中包含的输入 Token 数
     */
    // Step 3: 累加输入 Token
    // 原因：流式响应中，每个数据块可能包含部分 Token 计数
    public void addInput(int tokens) {
        // 使用 += 累加，而非赋值，确保累积所有块的 Token 数
        this.input += tokens;
    }

    /**
     * 累加输出 Token 数。
     *
     * @param tokens 本次流式块中包含的输出 Token 数
     */
    public void addOutput(int tokens) {
        this.output += tokens;
    }

    /**
     * 累加缓存读取 Token 数。
     *
     * @param tokens 本次流式块中包含的缓存读取 Token 数
     */
    public void addCacheRead(int tokens) {
        this.cacheRead += tokens;
    }

    /**
     * 累加缓存写入 Token 数。
     *
     * @param tokens 本次流式块中包含的缓存写入 Token 数
     */
    public void addCacheWrite(int tokens) {
        this.cacheWrite += tokens;
    }

    /**
     * 设置累计费用（通常在流结束后设置）。
     *
     * @param cost 费用详情
     */
    // Step 4: 设置费用，使用 setter 而非 add 方法
    // 原因：费用通常在流结束后由框架根据 Token 总量和模型单价统一计算，而非逐块累加
    public void setCost(Usage.Cost cost) {
        this.cost = cost;
    }

    /**
     * Computes total tokens as the sum of all token fields.
     * 计算总 Token 数，为所有 Token 字段之和。
     *
     * @return 总 Token 数
     */
    // Step 5: 计算总 Token 数
    // 原因：总 Token 数由各维度计数相加得出，避免调用方自行计算
    public int computeTotalTokens() {
        return input + output + cacheRead + cacheWrite;
    }

    /**
     * Converts this mutable accumulator to an immutable {@link Usage} record.
     * 将当前可变累加器转换为不可变的 {@link Usage} record。
     *
     * @return 不可变的 Usage 实例
     */
    // Step 6: 转换为不可变 Usage 实例
    // 原因：流结束后，将可变累加器"冻结"为不可变对象，确保后续使用中的线程安全
    public Usage toUsage() {
        return new Usage(input, output, cacheRead, cacheWrite, computeTotalTokens(), cost);
    }

    // --- Getters --- 字段访问方法

    public int getInput() {
        return input;
    }

    public int getOutput() {
        return output;
    }

    public int getCacheRead() {
        return cacheRead;
    }

    public int getCacheWrite() {
        return cacheWrite;
    }

    public Usage.Cost getCost() {
        return cost;
    }
}