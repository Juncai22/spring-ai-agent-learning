package com.pi.ai.core.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.Usage;
import com.pi.ai.core.util.PiAiJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型注册表 — 管理所有已注册的大语言模型（LLM）模型定义。
 *
 * <p>核心职责：
 * <ul>
 *   <li>在类加载时从 classpath 的 {@code /models.json} 资源文件加载预生成模型定义</li>
 *   <li>提供按 provider/modelId 查询模型的方法</li>
 *   <li>提供基于模型定价和 token 用量计算费用的方法</li>
 *   <li>提供模型能力检测（如是否支持 xhigh 思考级别）</li>
 *   <li>提供模型比较方法</li>
 * </ul>
 *
 * <p>数据结构：{@code provider -> (modelId -> Model)} 两级映射，
 * 第一层按服务提供商分组，第二层按模型 ID 分组。
 * 使用 {@link LinkedHashMap} 保持插入顺序，确保遍历结果可预测。
 *
 * <p>线程安全：注册表在类加载时一次性初始化，之后只读访问，天然线程安全。
 *
 * <p>设计为 final 工具类，构造方法私有，禁止实例化。
 *
 * @see Model
 * @see Usage.Cost
 * @see PiAiJson
 */
public final class ModelRegistry {

    // ============================================================
    // 日志与常量
    // ============================================================

    /** SLF4J 日志记录器，用于记录模型加载过程中的警告和错误 */
    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    // ============================================================
    // 注册表存储结构
    // ============================================================

    /**
     * 模型注册表存储结构。
     * <p>外层 Map：键为 provider 名称（如 "anthropic"、"openai"），
     * 值为该 provider 下所有模型的映射。
     * <p>内层 Map：键为 modelId（如 "claude-sonnet-4-20250514"），
     * 值为 {@link Model} 对象。
     *
     * 【数据结构说明】
     * Map<String, Map<String, Model>>
     * ┌──────────────────────────────────────────────┐
     * │  "anthropic"  →  Map<String, Model>          │
     * │                  ├── "claude-sonnet-4-..." → Model  │
     * │                  └── "claude-opus-4-..."   → Model  │
     * │  "openai"      →  Map<String, Model>          │
     * │                  ├── "gpt-4o"              → Model  │
     * │                  └── "gpt-4-turbo"         → Model  │
     * └──────────────────────────────────────────────┘
     *
     * 【为什么用 LinkedHashMap 而不是 HashMap？】
     * - LinkedHashMap 保持插入顺序，遍历顺序与 models.json 中的定义顺序一致
     * - 在 UI 展示或序列化时，可预测的顺序有利于调试和排查
     * - HashMap 不保证顺序，每次遍历可能不同，不利于前后端对齐
     */
    private static final Map<String, Map<String, Model>> registry;

    // ============================================================
    // 静态初始化（类加载时执行）
    // ============================================================

    /**
     * 静态初始化块：在类加载时从 classpath 的 {@code /models.json} 文件加载模型定义。
     * <p>如果资源文件不存在或加载失败，注册表为空，不影响应用启动。
     *
     * 【生命周期流程】
     * 1. JVM 加载 ModelRegistry 类时执行 static 块
     * 2. 调用 loadModels() 从 classpath 读取 /models.json
     * 3. 解析 JSON 构建两级 Map 结构
     * 4. 初始化完成后，registry 变为只读，后续所有 get 操作无需加锁
     * 5. 应用运行期间，getModel() 始终从内存读取，无 IO 开销
     */
    static {
        registry = loadModels();
    }

    // ============================================================
    // 构造方法（私有，禁止实例化）
    // ============================================================

    /**
     * 私有构造方法，防止外部实例化。
     * <p>此类为纯静态工具类，所有方法均通过类名直接调用。
     */
    private ModelRegistry() {
        // 工具类，禁止实例化
    }

    // ============================================================
    // 模型查找方法
    // ============================================================

    /**
     * 按 provider 和 modelId 查找模型定义。
     *
     * <p>查找过程分两步：
     * <ol>
     *   <li>先按 provider 名称查找该提供商下的所有模型映射</li>
     *   <li>再按 modelId 在该映射中查找具体模型</li>
     * </ol>
     *
     * <p>如果 provider 不存在或 modelId 不存在，均返回 null。
     *
     * @param provider 服务提供商标识，如 {@code "anthropic"}、{@code "openai"}
     * @param modelId  模型唯一标识，如 {@code "claude-sonnet-4-20250514"}、{@code "gpt-4o"}
     * @return 匹配的 Model 定义；未找到时返回 null
     */
    // 【模型查找流程 - 详细步骤】
    // 第一步：从外层 Map 中按 provider 查找
    //   registry.get("anthropic") → Map<String, Model>（该 provider 下的所有模型）
    //   如果 provider 不存在，返回 null，查找结束
    //
    // 第二步：从内层 Map 中按 modelId 查找
    //   providerModels.get("claude-sonnet-4-20250514") → Model 对象
    //   如果 modelId 不存在，返回 null，查找结束
    //
    // 【查找示例】
    //   getModel("anthropic", "claude-sonnet-4-20250514")
    //   → registry["anthropic"]["claude-sonnet-4-20250514"]
    //   → 返回对应的 Model 对象
    //
    // 【空安全设计】
    //   - provider 不存在时：providerModels == null，直接返回 null
    //   - modelId 不存在时：providerModels.get(modelId) 返回 null
    //   - 两种失败情况均返回 null，调用方需自行判空
    public static Model getModel(String provider, String modelId) {
        // 第一步：按 provider 名称查找对应的模型映射
        Map<String, Model> providerModels = registry.get(provider);
        // 如果该 provider 未注册，直接返回 null
        if (providerModels == null) {
            return null;
        }
        // 第二步：在该 provider 的映射中按 modelId 查找具体模型
        return providerModels.get(modelId);
    }

    // ============================================================
    // 列表查询方法
    // ============================================================

    /**
     * 返回所有已注册 Provider 的名称列表。
     *
     * <p>返回的列表为当前注册表键集的不可变快照，
     * 包含所有已加载模型定义的服务提供商名称。
     *
     * @return 不可变的 provider 名称列表，可能为空；不会返回 null
     */
    // 【实现说明】List.copyOf() 创建不可变副本，防止调用方意外修改注册表
    public static List<String> getProviders() {
        return List.copyOf(registry.keySet());
    }

    /**
     * 返回指定 Provider 下所有已注册的模型列表。
     *
     * @param provider 服务提供商标识，如 {@code "anthropic"}
     * @return 该 provider 下所有模型的不可变列表；provider 不存在时返回空列表
     */
    // 【实现说明】
    // 1. 先按 provider 查找映射，不存在时返回 Collections.emptyList()（空列表，非 null）
    // 2. 存在时返回 List.copyOf() 不可变副本
    // 【空安全】provider 不存在时返回空列表而非 null，调用方无需判空
    public static List<Model> getModels(String provider) {
        Map<String, Model> providerModels = registry.get(provider);
        if (providerModels == null) {
            return Collections.emptyList();
        }
        return List.copyOf(providerModels.values());
    }

    // ============================================================
    // 费用计算
    // ============================================================

    /**
     * 根据模型定价信息和 token 用量计算本次调用的费用。
     *
     * <p>计算公式：{@code cost = (price / 1_000_000) * tokens}
     * <p>其中 price 以每百万 token 为单位，除以 1,000,000 得到每 token 单价。
     *
     * <p>费用构成：
     * <ul>
     *   <li>输入费用（input）：基于输入 token 数量和输入单价</li>
     *   <li>输出费用（output）：基于输出 token 数量和输出单价</li>
     *   <li>缓存读取费用（cacheRead）：基于缓存命中的 token 数量和缓存读取单价</li>
     *   <li>缓存写入费用（cacheWrite）：基于缓存写入的 token 数量和缓存写入单价</li>
     *   <li>总费用（total）：以上四项之和</li>
     * </ul>
     *
     * <p>如果模型没有定价信息（{@link Model#cost()} 返回 null），
     * 返回全零费用（所有字段均为 0.0），避免空指针异常。
     *
     * @param model 模型定义，包含每百万 token 的定价信息
     * @param usage token 用量统计，包含输入/输出/缓存读取/缓存写入的 token 数
     * @return 费用明细对象，包含各项费用和总费用；cost 为 null 时返回全零费用
     */
    // 【费用计算流程】
    // 1. 检查 model.cost() 是否为空，为空则返回全零费用（空安全兜底）
    // 2. 分别计算 input / output / cacheRead / cacheWrite 四项费用
    //    公式：费用 = (单价 / 1,000,000) × 实际 token 数
    //    单价以每百万 token 计价，故先除以 1,000,000 得到每 token 的单价
    // 3. 四项费用相加得到总费用
    // 4. 返回 Usage.Cost 记录对象
    //
    // 【计算示例】
    //   输入单价: $3.00 / 1M tokens，输入 token: 500
    //   → input = (3.00 / 1000000) × 500 = $0.0015
    //
    // 【空安全】model.cost() 为 null 时返回全零费用，避免 NPE
    public static Usage.Cost calculateCost(Model model, Usage usage) {
        // 空安全：模型没有定价信息时，返回全零费用
        if (model.cost() == null) {
            return new Usage.Cost(0.0, 0.0, 0.0, 0.0, 0.0);
        }
        // 计算输入费用：输入单价 ÷ 1,000,000 × 输入 token 数
        double input = (model.cost().input() / 1000000.0) * usage.input();
        // 计算输出费用：输出单价 ÷ 1,000,000 × 输出 token 数
        double output = (model.cost().output() / 1000000.0) * usage.output();
        // 计算缓存读取费用：缓存读取单价 ÷ 1,000,000 × 缓存读取 token 数
        double cacheRead = (model.cost().cacheRead() / 1000000.0) * usage.cacheRead();
        // 计算缓存写入费用：缓存写入单价 ÷ 1,000,000 × 缓存写入 token 数
        double cacheWrite = (model.cost().cacheWrite() / 1000000.0) * usage.cacheWrite();
        // 计算总费用：四项费用之和
        double total = input + output + cacheRead + cacheWrite;
        // 返回费用明细记录
        return new Usage.Cost(input, output, cacheRead, cacheWrite, total);
    }

    // ============================================================
    // 模型能力检测
    // ============================================================

    /**
     * 检测模型是否支持 xhigh（极限高）思考级别。
     *
     * <p>xhigh 是比 high 更高级别的推理深度，适用于需要更长链式思考的复杂任务。
     * 当前支持 xhigh 的模型系列如下：
     * <ul>
     *   <li>GPT-5.2 / GPT-5.3 / GPT-5.4 系列 — OpenAI 最新高推理能力模型</li>
     *   <li>Opus 4.6 / Opus 4.6 系列 — Anthropic 顶级推理模型</li>
     * </ul>
     *
     * <p>判断逻辑：通过检查模型 ID 是否包含上述系列的关键字来判定。
     * 随着模型更新，此方法可能需要同步更新。
     *
     * @param model 模型定义
     * @return 如果模型支持 xhigh 思考级别返回 true，否则返回 false
     */
    // 【xhigh 检测流程】
    // 1. 获取 model.id() 字符串
    // 2. 依次检查是否包含 "gpt-5.2"、"gpt-5.3"、"gpt-5.4" 关键字
    // 3. 再检查是否包含 "opus-4-6"、"opus-4.6" 关键字
    // 4. 命中任一关键字返回 true，否则返回 false
    // 【维护注意】模型命名规则可能随版本变化，需定期同步更新
    // 【设计考量】基于字符串包含判断而非注册表字段，避免复杂的模型能力定义数据结构
    public static boolean supportsXhigh(Model model) {
        String id = model.id();
        // 检查 OpenAI GPT-5.x 系列模型
        if (id.contains("gpt-5.2") || id.contains("gpt-5.3") || id.contains("gpt-5.4")) {
            return true;
        }
        // 检查 Anthropic Opus 4.6 系列模型（支持两种命名格式：连字符和点号）
        if (id.contains("opus-4-6") || id.contains("opus-4.6")) {
            return true;
        }
        // 非以上模型系列，不支持 xhigh
        return false;
    }

    // ============================================================
    // 模型比较
    // ============================================================

    /**
     * 通过比较 {@code id} 和 {@code provider} 字段判断两个模型是否相等。
     *
     * <p>比较规则：
     * <ul>
     *   <li>任一参数为 null 时，返回 false（空安全）</li>
     *   <li>两个模型的 {@link Model#id()} 和 {@link Model#provider()} 均相等时，返回 true</li>
     *   <li>不比较其他字段（如 cost、capabilities 等）</li>
     * </ul>
     *
     * @param a 模型 a，可为 null
     * @param b 模型 b，可为 null
     * @return 两个模型的 id 和 provider 均相等时返回 true；任一参数为 null 时返回 false
     */
    // 【比较逻辑】
    // 1. 空安全：任一参数为 null 直接返回 false
    // 2. 比较 id 和 provider 两个字段，均相等才视为同一个模型
    // 3. 不比较 cost、capabilities 等字段，因为这些字段可能随定价更新而变化
    // 【设计意图】模型的身份由 (provider, id) 二元组唯一确定，与定价等属性无关
    public static boolean modelsAreEqual(Model a, Model b) {
        // 空安全：任一参数为 null 直接返回 false
        if (a == null || b == null) {
            return false;
        }
        // 比较 id 和 provider 两个关键字段
        return a.id().equals(b.id()) && a.provider().equals(b.provider());
    }

    // ============================================================
    // 内部方法：模型数据加载
    // ============================================================

    /**
     * 从 classpath 的 {@code /models.json} 资源文件加载模型定义。
     *
     * <p>加载流程：
     * <ol>
     *   <li>通过 {@link Class#getResourceAsStream(String)} 获取资源文件输入流</li>
     *   <li>使用 Jackson {@link PiAiJson#MAPPER} 反序列化为 {@code Map<String, Map<String, Model>>}</li>
     *   <li>使用 {@link LinkedHashMap} 包装内外层 Map，保持插入顺序</li>
     *   <li>如果资源文件不存在或解析失败，记录警告/错误日志并返回空 Map</li>
     * </ol>
     *
     * <p>{@code models.json} 文件结构示例：
     * <pre>{@code
     * {
     *   "anthropic": {
     *     "claude-sonnet-4-20250514": { "id": "...", "provider": "anthropic", ... },
     *     "claude-opus-4-20250514": { "id": "...", "provider": "anthropic", ... }
     *   },
     *   "openai": {
     *     "gpt-4o": { "id": "...", "provider": "openai", ... }
     *   }
     * }
     * }</pre>
     *
     * @return 加载后的模型注册表；加载失败时返回空 Map，不会返回 null
     */
    // 【模型数据加载流程 - 详细步骤】
    // 第一步：获取资源文件输入流
    //   ModelRegistry.class.getResourceAsStream("/models.json")
    //   从 classpath 根目录查找 models.json 文件
    //   如果文件不存在，is 为 null，记录警告日志并返回空 Map
    //
    // 第二步：JSON 反序列化
    //   PiAiJson.MAPPER.readValue(is, new TypeReference<...>() {})
    //   使用 Jackson 将 JSON 解析为 Map<String, Map<String, Model>>
    //   TypeReference 用于保留泛型类型信息，避免类型擦除
    //
    // 第三步：保持插入顺序
    //   将原始 Map 的每个条目重新放入 LinkedHashMap
    //   确保内外层 Map 均保持插入顺序，遍历结果可预测
    //
    // 第四步：异常处理
    //   资源不存在 → 记录 warn 日志，返回空 Map
    //   解析失败 → 记录 error 日志（含异常堆栈），返回空 Map
    //   两种失败情况均返回 Collections.emptyMap()，不会返回 null
    //
    // 【设计考量】
    // - 使用 try-with-resources 确保 InputStream 自动关闭，避免资源泄漏
    // - 失败不抛异常，降级为空注册表，不影响应用启动
    private static Map<String, Map<String, Model>> loadModels() {
        // 第一步：从 classpath 获取 /models.json 资源文件
        try (InputStream is = ModelRegistry.class.getResourceAsStream("/models.json")) {
            // 资源文件不存在时，记录警告并返回空 Map
            if (is == null) {
                log.warn("models.json 资源文件未找到，模型注册表为空");
                return Collections.emptyMap();
            }

            // 第二步：使用 Jackson 反序列化 JSON 为两级 Map
            // TypeReference 用于保留 Map<String, Map<String, Model>> 泛型信息
            Map<String, Map<String, Model>> raw = PiAiJson.MAPPER.readValue(
                    is,
                    new TypeReference<Map<String, Map<String, Model>>>() { }
            );

            // 第三步：使用 LinkedHashMap 包装，保持插入顺序
            Map<String, Map<String, Model>> result = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Model>> entry : raw.entrySet()) {
                // 外层：provider → LinkedHashMap（保持该 provider 下模型的顺序）
                // 内层：modelId → Model（同样用 LinkedHashMap 保持顺序）
                result.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
            }

            // 记录成功加载的 provider 数量（debug 级别，生产环境不输出）
            log.debug("已加载 {} 个 provider 的模型定义", result.size());
            return result;

        } catch (Exception e) {
            // 第四步：异常处理 — 解析失败时记录错误日志，返回空 Map
            // 涵盖：JSON 格式错误、字段类型不匹配、IO 异常等
            log.error("加载 models.json 失败，模型注册表将为空", e);
            return Collections.emptyMap();
        }
    }
}