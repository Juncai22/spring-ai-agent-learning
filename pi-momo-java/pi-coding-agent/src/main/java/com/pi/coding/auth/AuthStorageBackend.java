package com.pi.coding.auth;

import java.util.Map;

/**
 * 认证凭证持久化后端接口，定义了凭证数据的存储协议。
 *
 * <p>AuthStorageBackend 是 {@link AuthStorage} 的底层存储抽象，负责实际的凭证数据读写操作。
 * 通过策略模式，AuthStorage 可以灵活切换不同的存储实现，而不影响上层业务逻辑。
 *
 * <p>内置实现：
 * <ul>
 *   <li>{@link FileAuthStorageBackend} - 文件持久化实现，适用于生产环境</li>
 *   <li>{@link InMemoryAuthStorageBackend} - 内存存储实现，适用于测试环境</li>
 * </ul>
 *
 * <p>自定义实现可以扩展此接口，例如实现数据库存储、加密存储、云存储等场景。
 *
 * @see AuthStorage
 * @see FileAuthStorageBackend
 * @see InMemoryAuthStorageBackend
 */
public interface AuthStorageBackend {
    
    /**
     * 加载所有已存储的认证凭证。
     *
     * <p>从持久化存储中读取全部凭证数据，返回以提供商 ID 为键的映射。
     * 如果存储为空或不存在，应返回空映射而非 null。
     *
     * @return 提供商 ID 到认证凭证的映射，永远不会返回 null
     */
    Map<String, AuthCredential> load();
    
    /**
     * 保存所有认证凭证，替换任何已存在的旧数据。
     *
     * <p>此操作是全量覆盖，调用方（AuthStorage）会传入完整的凭证映射，
     * 实现类应使用新数据完全替换旧的存储内容，而非增量更新。
     *
     * @param credentials 提供商 ID 到认证凭证的完整映射
     */
    void save(Map<String, AuthCredential> credentials);
}
