package com.pi.coding.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.ai.core.util.PiAiJson;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 基于文件系统的认证凭证持久化后端实现。
 *
 * <p>凭证以 JSON 格式存储到指定的文件中，支持漂亮的格式化输出便于阅读和调试。
 * 使用 Java NIO 的文件锁（FileLock）机制防止多进程并发访问同一文件时的数据竞争。
 *
 * <p>主要特性：
 * <ul>
 *   <li>JSON 序列化/反序列化凭证数据，利用 Jackson 的多态类型支持</li>
 *   <li>自动创建父目录，确保文件路径有效</li>
 *   <li>文件锁保护读/写操作，支持跨进程安全访问</li>
 *   <li>文件损坏或不可读时自动降级，返回空映射而非抛出异常</li>
 * </ul>
 *
 * <p>线程安全：通过文件锁确保同一时间只有一个进程可以写入文件，
 * 读取操作使用共享锁（非独占）以允许并发读取。
 *
 * @see AuthStorageBackend
 * @see AuthStorage
 */
public class FileAuthStorageBackend implements AuthStorageBackend {
    
    /** Jackson ObjectMapper 实例，用于凭证对象的 JSON 序列化和反序列化。 */
    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;
    /** 凭证映射的类型引用，用于泛型反序列化：Map<String, AuthCredential>。 */
    private static final TypeReference<Map<String, AuthCredential>> CREDENTIALS_TYPE =
        new TypeReference<>() {};

    /** 凭证文件路径，存储 JSON 格式的凭证数据。 */
    private final Path filePath;
    
    /**
     * 使用字符串路径创建文件后端实例。
     *
     * @param filePath 凭证文件的路径字符串
     */
    public FileAuthStorageBackend(String filePath) {
        this.filePath = Path.of(Objects.requireNonNull(filePath, "filePath must not be null"));
    }
    
    /**
     * 使用字符串路径创建文件后端实例。
     *
     * @param filePath 凭证文件的路径字符串
     */
    public FileAuthStorageBackend(Path filePath) {
        this.filePath = Objects.requireNonNull(filePath, "filePath must not be null");
    }
    
    @Override
    public Map<String, AuthCredential> load() {
        // 如果文件不存在，返回空映射而非抛出异常
        if (!Files.exists(filePath)) {
            return new HashMap<>();
        }
        
        try {
            // 在文件锁保护下读取文件内容并反序列化为凭证映射
            return withFileLock(false, () -> {
                String content = Files.readString(filePath);
                // 文件内容为空时返回空映射
                if (content.isBlank()) {
                    return new HashMap<>();
                }
                Map<String, AuthCredential> loaded = MAPPER.readValue(content, CREDENTIALS_TYPE);
                return loaded != null ? new HashMap<>(loaded) : new HashMap<>();
            });
        } catch (IOException e) {
            // 文件损坏或不可读时降级处理：返回空映射，不中断程序运行
            return new HashMap<>();
        }
    }
    
    @Override
    public void save(Map<String, AuthCredential> credentials) {
        try {
            // 确保父目录存在，如果不存在则自动创建
            Path parent = filePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            
            // 在写锁保护下执行序列化和写入操作
            withFileLock(true, () -> {
                String json = MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(credentials);
                Files.writeString(filePath, json);
                return null;
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to save credentials to " + filePath, e);
        }
    }
    
    /**
     * 在文件锁的保护下执行指定操作，确保并发安全。
     *
     * <p>使用 Java NIO 的 FileChannel 和 FileLock 实现文件级别的锁定。
     * 写操作使用独占锁（exclusive=true），阻止其他进程同时读写；
     * 读操作使用共享锁（exclusive=false），允许多个进程同时读取。
     *
     * <p>如果文件不存在，会自动创建文件并初始化为空 JSON 对象 "{}"。
     * 文件锁在 try-with-resources 块中自动释放，无需手动关闭。
     *
     * @param <T>       操作返回值的类型
     * @param exclusive  是否为独占锁（true=写锁，false=读锁）
     * @param operation  需要在锁保护下执行的操作
     * @return 操作的执行结果
     * @throws IOException 如果文件操作失败
     */
    private <T> T withFileLock(boolean exclusive, IOSupplier<T> operation) throws IOException {
        // 确保文件存在，创建空 JSON 对象作为初始内容
        if (!Files.exists(filePath)) {
            Path parent = filePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(filePath, "{}");
        }
        
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), exclusive ? "rw" : "r");
             FileChannel channel = raf.getChannel();
             FileLock lock = channel.lock(0, Long.MAX_VALUE, !exclusive)) {
            return operation.get();
        }
    }
    
    /**
     * 内部函数式接口，允许抛出 IO 异常的操作定义。
     *
     * <p>标准 Java 的 Supplier 接口不允许抛出受检异常，
     * 此接口用于在文件锁操作中传递可能抛出 IOException 的 lambda 表达式。
     *
     * @param <T> 操作返回值的类型
     */
    @FunctionalInterface
    private interface IOSupplier<T> {
        /** 执行操作，可能抛出 IOException。 */
        T get() throws IOException;
    }
}
