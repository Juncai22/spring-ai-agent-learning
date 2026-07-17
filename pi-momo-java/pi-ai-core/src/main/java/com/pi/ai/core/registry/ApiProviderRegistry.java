package com.pi.ai.core.registry;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API Provider 全局注册表，采用静态单例模式管理所有已注册的 {@link ApiProvider} 实现。
 *
 * <p>核心职责：
 * <ul>
 *   <li>提供 {@link #register(ApiProvider, String)} 注册 API Provider 实例</li>
 *   <li>提供 {@link #get(String)} 按 API 协议标识查找已注册的 Provider</li>
 *   <li>提供 {@link #unregister(String)} 按来源标识批量注销 Provider</li>
 *   <li>提供 {@link #clear()} 清空注册表</li>
 * </ul>
 *
 * <p>线程安全：使用 {@link ConcurrentHashMap} 作为底层存储，保证并发场景下的读写安全，
 * 与 TypeScript 版本的模块级变量语义一致。
 *
 * <p>{@code sourceId} 机制：每个 Provider 注册时携带来源标识，支持按来源批量注销。
 * 这在热插拔（hot-reload）场景中尤为重要——当某个模块重新加载时，
 * 可以一键注销该模块注册的所有 Provider，避免残留。
 *
 * <p>设计为 final 工具类，构造方法私有，禁止实例化。
 *
 * @see ApiProvider
 * @see ConcurrentHashMap
 */
public final class ApiProviderRegistry {

    // ============================================================
    // 常量定义
    // ============================================================

    /** 默认来源标识，用于不指定来源的 {@link #register(ApiProvider)} 重载方法 */
    private static final String DEFAULT_SOURCE_ID = "default";

    // ============================================================
    // 内部数据结构
    // ============================================================

    /**
     * 已注册 Provider 的内部包装记录。
     * <p>将实际 Provider 实例与注册来源标识绑定，便于按来源批量注销。
     *
     * @param provider 实际的 ApiProvider 实例
     * @param sourceId 注册来源标识，用于 {@link #unregister(String)} 批量操作
     */
    // 【Java 16+ Record】自动生成构造方法、getter、equals()、hashCode()、toString()
    // 【设计意图】将 provider 实例与 sourceId 绑定，支持按来源批量注销
    private record RegisteredProvider(ApiProvider provider, String sourceId) {}

    /**
     * 已注册 Provider 的存储容器。
     * <p>键为 {@link ApiProvider#api()} 返回的协议标识字符串，值为包装后的 RegisteredProvider 记录。
     * 使用 ConcurrentHashMap 保证线程安全。
     *
     * 【为什么使用 ConcurrentHashMap 而不是 HashMap 或 Hashtable？】
     * 1. HashMap 线程不安全：多线程并发 put 会导致死循环（JDK 7 头插法）或数据丢失，
     *    多线程 get 可能读到过期值（happens-before 未建立）。
     * 2. Hashtable 线程安全但性能差：所有方法均使用 synchronized 加锁（类级别锁），
     *    并发读写时所有线程串行化，竞争激烈时吞吐量急剧下降。
     * 3. ConcurrentHashMap 采用分段锁（JDK 7）或 CAS + synchronized（JDK 8+）策略：
     *    - 读操作（get()）无锁，依靠 volatile 读保证可见性
     *    - 写操作（put()）仅对特定桶加锁，不同桶的写操作可以并发执行
     *    - 不会抛出 ConcurrentModificationException，迭代器弱一致性（weakly consistent）
     *    - 整体吞吐量远高于 Hashtable，适用于高并发注册/查找场景
     *
     * 【调用关系说明】
     * register() 内部调用 providers.put(provider.api(), new RegisteredProvider(...))
     * get() 内部调用 providers.get(api) 并解包返回 provider
     * 二者通过 ConcurrentHashMap 的 key（即 provider.api() 返回值）建立关联，
     * 形成 "按协议标识注册 -> 按协议标识查找" 的完整生命周期。
     * 注册时 provider.api() 决定了查找时的 key，因此必须保证 api() 返回值稳定不变。
     */
    private static final ConcurrentHashMap<String, RegisteredProvider> providers = new ConcurrentHashMap<>();

    // ============================================================
    // 构造方法（私有，禁止实例化）
    // ============================================================

    /**
     * 私有构造方法，防止外部实例化。
     * <p>此类为纯静态工具类，所有方法均通过类名直接调用。
     */
    private ApiProviderRegistry() {
        // 工具类，禁止实例化
    }

    // ============================================================
    // 注册方法
    // ============================================================

    /**
     * 注册 API Provider，指定来源标识。
     *
     * <p>如果同一 API 协议标识已存在，新 Provider 会覆盖旧 Provider。
     * 调用方可通过 {@link #unregister(String)} 按 sourceId 批量注销。
     *
     * <p>使用示例：
     * <pre>{@code
     * ApiProviderRegistry.register(new AnthropicProvider(), "my-plugin");
     * }</pre>
     *
     * @param provider 要注册的 Provider 实例，不可为 null
     * @param sourceId 注册来源标识，用于后续按来源批量注销；不可为 null
     */
    // 【注册流程】
    // 1. 调用 provider.api() 获取协议标识（如 "anthropic-messages"）作为 ConcurrentHashMap 的 key
    // 2. 将 provider 和 sourceId 包装为 RegisteredProvider record 作为 value
    // 3. 调用 ConcurrentHashMap.put(key, value) 存入注册表
    // 4. 如果同一 key 已存在，旧值被覆盖并返回（本方法忽略返回值）
    // 【线程安全】ConcurrentHashMap.put() 使用 CAS + 桶级锁，多线程并发注册互不干扰
    // 【调用关系】此方法写入的数据，后续由 get() 方法通过相同的 key 读取
    public static void register(ApiProvider provider, String sourceId) {
        providers.put(provider.api(), new RegisteredProvider(provider, sourceId));
    }

    /**
     * 注册 API Provider，使用默认来源标识 {@code "default"}。
     *
     * <p>此方法为简化版本，保持向后兼容，等价于 {@code register(provider, "default")}。
     * 适用于不需要按来源管理 Provider 生命周期的场景。
     *
     * @param provider 要注册的 Provider 实例，不可为 null
     */
    // 【简化重载】委托给 register(provider, DEFAULT_SOURCE_ID)，sourceId 固定为 "default"
    // 【用途】保持向后兼容性，旧代码调用此方法时不需传入 sourceId
    public static void register(ApiProvider provider) {
        register(provider, DEFAULT_SOURCE_ID);
    }

    // ============================================================
    // 查找方法
    // ============================================================

    /**
     * 按 API 协议标识查找已注册的 Provider。
     *
     * <p>查找键为 {@link ApiProvider#api()} 返回的协议标识字符串。
     * 如果未找到对应注册，返回 null。
     *
     * @param api API 协议标识，如 {@code "anthropic-messages"}、{@code "openai-chat"}
     * @return 对应的 Provider 实例；未注册时返回 null
     */
    // 【查找流程】
    // 1. 调用 providers.get(api) 在 ConcurrentHashMap 中查找
    // 2. ConcurrentHashMap.get() 是无锁操作，依赖 volatile 读保证可见性
    // 3. 如果找到（rp != null），解包取出 RegisteredProvider.provider() 返回
    // 4. 如果未找到，返回 null
    // 【调用关系】与 register() 通过相同的 key（provider.api()）配对
    // 【时序安全】即使 register() 和 get() 在不同线程并发执行，
    //           ConcurrentHashMap 保证 get() 要么看到旧值，要么看到新值，不会读到中间状态
    public static ApiProvider get(String api) {
        var rp = providers.get(api);
        return rp != null ? rp.provider() : null;
    }

    /**
     * 返回所有已注册的 Provider 列表。
     *
     * <p>返回的列表为当前注册表快照的不可变副本，
     * 后续对注册表的修改不会影响已返回的列表。
     *
     * @return 不可变的 Provider 列表，可能为空；不会返回 null
     */
    // 【快照机制】通过 values().stream() 获取当前注册表快照，转换为不可变列表
    // 【弱一致性】ConcurrentHashMap.values() 返回的视图是弱一致性的，
    //           但 stream().toList() 在此处完成了一次快照，后续变更不影响已返回列表
    // 【空安全】注册表为空时返回空列表，不会返回 null
    public static List<ApiProvider> getAll() {
        return providers.values().stream()
                .map(RegisteredProvider::provider)
                .toList();
    }

    // ============================================================
    // 注销方法
    // ============================================================

    /**
     * 按来源标识批量注销 Provider。
     *
     * <p>移除所有使用指定 sourceId 注册的 Provider 条目。
     * 如果没有任何 Provider 使用该 sourceId，则无操作。
     *
     * <p>典型使用场景：在模块热加载（hot-reload）时，
     * 先调用 {@code unregister(oldSourceId)} 清理旧实例，
     * 再调用 {@link #register(ApiProvider, String)} 注册新实例。
     *
     * @param sourceId 要注销的来源标识，与注册时传入的 sourceId 对应
     */
    // 【批量注销流程】
    // 1. 调用 ConcurrentHashMap.entrySet().removeIf() 遍历所有条目
    // 2. 对每个条目，比较其 value（RegisteredProvider）的 sourceId 是否匹配
    // 3. 匹配的条目被原子性移除
    // 4. 没有任何匹配时，removeIf 返回 false，无副作用
    // 【线程安全】ConcurrentHashMap 的 entrySet().removeIf() 是原子操作，
    //           遍历期间其他线程的 put/remove 不会导致 ConcurrentModificationException
    // 【性能注意】removeIf 需要遍历整个注册表，sourceId 越多，注册表越大，开销越大
    public static void unregister(String sourceId) {
        providers.entrySet().removeIf(e -> e.getValue().sourceId().equals(sourceId));
    }

    /**
     * 清空所有已注册的 Provider。
     *
     * <p>此操作会移除注册表中所有条目，通常在应用关闭或重置时使用。
     * 清空后，所有 {@link #get(String)} 调用将返回 null，直到重新注册。
     */
    // 【清空流程】委托 ConcurrentHashMap.clear() 清空所有条目
    // 【线程安全】ConcurrentHashMap.clear() 是原子操作，分段清空但不影响并发读
    // 【副作用】清空后 get() 所有 key 均返回 null，getAll() 返回空列表
    public static void clear() {
        providers.clear();
    }
}