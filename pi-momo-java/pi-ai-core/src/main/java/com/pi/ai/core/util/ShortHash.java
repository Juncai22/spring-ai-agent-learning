package com.pi.ai.core.util;

/**
 * 确定性短哈希工具，使用双哈希算法 + Base36 编码生成固定长度的短哈希值。
 *
 * <p>核心特性：
 * <ul>
 *   <li><b>确定性</b>：相同的输入始终产生相同的哈希值，保证可重现</li>
 *   <li><b>双哈希</b>：使用两个不同种子的独立哈希函数（h1, h2），提高哈希质量</li>
 *   <li><b>Base36 编码</b>：输出使用 0-9a-z 共 36 个字符，紧凑且可读性好</li>
 *   <li><b>Java 32 位截断乘法</b>：精确模拟 JavaScript 的 Math.imul 语义，确保跨语言一致性</li>
 * </ul>
 *
 * <p>对应 TypeScript 中的 {@code utils/hash.ts}。
 */
public final class ShortHash {

    private ShortHash() {
        // 工具类，禁止实例化
    }

    /**
     * 计算字符串的确定性短哈希值。
     *
     * <p>算法步骤：
     * <ol>
     *   <li>初始化两个哈希值 h1 和 h2，使用不同的质数种子（0xdeadbeef 和 0x41c6ce57）</li>
     *   <li>遍历每个字符，分别用不同的大质数乘法常数（0x9E3779B1 和 0x5F356495）更新 h1 和 h2</li>
     *   <li>对 h1 和 h2 进行混合（mix）操作，增强雪崩效应（avalanche effect）</li>
     *   <li>将 h1 和 h2 分别转为无符号整数后以 Base36 编码拼接输出</li>
     * </ol>
     *
     * <p>注意：Java 的 int 乘法天然是 32 位截断的，等价于 JavaScript 的 Math.imul。
     * 使用 Integer.toUnsignedLong 模拟 JavaScript 的 {@code >>> 0}（无符号右移 0 位）操作，
     * 将有符号 int 转为无符号 long 后再进行 Base36 编码。
     *
     * @param str 输入字符串
     * @return Base36 编码的哈希值字符串（由 h2 和 h1 的 Base36 拼接而成）
     */
    public static String shortHash(String str) {
        // ========== 步骤 1：初始化两个不同种子的哈希值 ==========
        int h1 = 0xdeadbeef;  // 种子 1：著名的魔数 3735928495（十六进制）
        int h2 = 0x41c6ce57;  // 种子 2：不同的质数种子 1103546967

        // ========== 步骤 2：逐字符处理，分别更新两个哈希值 ==========
        for (int i = 0; i < str.length(); i++) {
            int ch = str.charAt(i);
            // 使用不同的质数乘法常数，确保两个哈希函数独立
            h1 = imul(h1 ^ ch, 0x9E3779B1); // 常数 1：黄金比例 2^32 的小数部分 2654435761
            h2 = imul(h2 ^ ch, 0x5F356495); // 常数 2：不同的质数常数 1597334677
        }

        // ========== 步骤 3：混合阶段 — 将 h1 和 h2 的信息互相扩散 ==========
        // 混合操作使每个输入位影响更多输出位（雪崩效应），提高哈希质量
        // 第一轮混合：h1 和 h2 各自右移后异或，再与另一个值混合
        h1 = imul(h1 ^ (h1 >>> 16), 0x85EBCA6B) ^ imul(h2 ^ (h2 >>> 13), 0xC2B2AE35);
        // 第二轮混合：使用上一轮混合后的值再次交叉混合
        h2 = imul(h2 ^ (h2 >>> 16), 0x85EBCA6B) ^ imul(h1 ^ (h1 >>> 13), 0xC2B2AE35);

        // ========== 步骤 4：Base36 编码输出 ==========
        // JavaScript 中的 >>> 0 将 signed int 转为 unsigned（0 到 4294967295）
        // Java 中使用 Integer.toUnsignedLong 实现相同效果，然后进行 Base36 编码
        // 输出格式：h2 的 Base36 + h1 的 Base36（h2 在前，h1 在后）
        return Long.toString(Integer.toUnsignedLong(h2), 36)
                + Long.toString(Integer.toUnsignedLong(h1), 36);
    }

    /**
     * 32 位截断乘法，等价于 JavaScript 的 Math.imul。
     *
     * <p>Java 的 int 类型是 32 位有符号整数，其乘法运算天然就是 32 位截断的，
     * 溢出时自动丢弃高位，与 JavaScript 的 Math.imul 语义完全一致。
     * 此方法仅作为语义明确的包装，提示读者此处有意使用 32 位截断乘法。
     *
     * @param a 乘数 a
     * @param b 乘数 b
     * @return a * b 的 32 位截断结果
     */
    private static int imul(int a, int b) {
        // Java int 乘法天然 32 位截断，等价于 JS Math.imul
        return a * b;
    }
}