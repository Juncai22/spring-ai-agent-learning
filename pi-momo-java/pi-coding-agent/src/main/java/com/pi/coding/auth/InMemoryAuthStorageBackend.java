package com.pi.coding.auth;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的认证凭证持久化后端实现，主要用于测试场景。
 *
 * <p>凭证数据存储在 JVM 堆内存中的 ConcurrentHashMap 中，JVM 退出后数据即丢失。
 * 使用 ConcurrentHashMap 确保线程安全，支持并发读写操作。
 *
 * <p>适用场景：
 * <ul>
 *   <li>单元测试和集成测试，避免在测试中产生文件残留</li>
 *   <li>临时性凭证存储，不需要跨进程持久化的场景</li>
 *   <li>快速原型开发阶段</li>
 * </ul>
 *
 * <p>注意：由于数据不持久化，生产环境中应使用 {@link FileAuthStorageBackend} 替代。
 *
 * @see AuthStorageBackend
 * @see FileAuthStorageBackend
 */
public class InMemoryAuthStorageBackend implements AuthStorageBackend {
    
    /** 线程安全的凭证存储映射，使用 ConcurrentHashMap 实现并发安全。 */
    private final Map<String, AuthCredential> credentials = new ConcurrentHashMap<>();
    
    @Override
    public Map<String, AuthCredential> load() {
        // 返回当前内存中所有凭证的快照副本，防止外部修改影响内部状态
        return new HashMap<>(credentials);
    }
    
    @Override
    public void save(Map<String, AuthCredential> credentials) {
        // 全量替换：清空旧数据后写入新数据，保证与文件存储的语义一致
        this.credentials.clear();
        this.credentials.putAll(credentials);
    }
}
