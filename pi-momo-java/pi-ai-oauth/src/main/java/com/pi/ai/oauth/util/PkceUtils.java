package com.pi.ai.oauth.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE（Proof Key for Code Exchange，代码交换证明密钥）工具类。
 *
 * <p>PKCE 是 OAuth 2.0 的一种安全扩展，用于增强授权码流程的安全性，防止授权码拦截攻击。
 * 它通过动态生成的 code_verifier 和 code_challenge 来确保只有发起请求的客户端才能使用授权码。
 *
 * <p>核心流程：
 * <ol>
 *   <li>客户端生成一个随机的 <b>code_verifier</b>（代码验证器）</li>
 *   <li>客户端计算 verifier 的 SHA-256 哈希，得到 <b>code_challenge</b>（代码挑战值）</li>
 *   <li>在授权请求中发送 code_challenge</li>
 *   <li>在令牌交换请求中发送原始的 code_verifier</li>
 *   <li>服务端校验 code_verifier 的哈希是否与 code_challenge 匹配</li>
 * </ol>
 *
 * <p>本工具类使用 32 字节（256 位）的加密级随机数，通过 SHA-256 哈希生成挑战值，
 * 并使用 Base64URL 编码（无填充）作为最终的编码格式。
 *
 * <p>对应 pi-mono 前端的 pkce.ts。
 */
public final class PkceUtils {

    /** 加密安全的随机数生成器，用于生成 code_verifier */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 私有构造器，防止实例化 */
    private PkceUtils() {}

    /**
     * 生成 PKCE 所需的 code_verifier 和 code_challenge。
     *
     * <p>生成步骤：
     * <ol>
     *   <li>生成 32 字节的加密级随机数作为 verifier 的原始字节</li>
     *   <li>将原始字节进行 Base64URL 编码（无填充），得到 code_verifier 字符串</li>
     *   <li>对 code_verifier 字符串进行 SHA-256 哈希运算</li>
     *   <li>将哈希结果进行 Base64URL 编码（无填充），得到 code_challenge 字符串</li>
     * </ol>
     *
     * <p>生成的 code_verifier 长度约为 43 个字符（32 字节 Base64URL 编码），
     * 符合 RFC 7636 中 43~128 字符的要求。
     *
     * @return 包含 verifier 和 challenge 的 PKCE 结果对象，不会为 null
     * @throws RuntimeException 如果当前 JVM 不支持 SHA-256 算法（理论上不会发生）
     */
    public static PkceResult generatePKCE() {
        // 生成 32 字节的随机数作为 code_verifier 的原始字节
        byte[] verifierBytes = new byte[32];
        RANDOM.nextBytes(verifierBytes);
        String verifier = base64UrlEncode(verifierBytes);

        // 计算 code_verifier 的 SHA-256 哈希，得到 code_challenge
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(verifier.getBytes(StandardCharsets.UTF_8));
            String challenge = base64UrlEncode(hashBytes);
            return new PkceResult(verifier, challenge);
        } catch (NoSuchAlgorithmException e) {
            // 所有 Java 实现都必须支持 SHA-256，此异常不会发生
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Base64URL 编码（无填充）。
     * <p>与标准 Base64 编码的区别：
     * <ul>
     *   <li>使用 '-' 和 '_' 替代 '+' 和 '/'，避免 URL 编码问题</li>
     *   <li>去掉末尾的 '=' 填充字符，使编码结果更紧凑</li>
     * </ul>
     * <p>符合 RFC 4648 第 5 节定义的 Base64URL 编码规范。
     *
     * @param bytes 要编码的原始字节数组，不可为 null
     * @return Base64URL 编码后的字符串，无填充字符
     */
    static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * PKCE 结果记录，包含生成的 code_verifier 和 code_challenge。
     *
     * @param verifier  代码验证器，用于后续的令牌交换请求
     * @param challenge 代码挑战值，用于授权请求中的安全校验
     */
    public record PkceResult(String verifier, String challenge) {}
}