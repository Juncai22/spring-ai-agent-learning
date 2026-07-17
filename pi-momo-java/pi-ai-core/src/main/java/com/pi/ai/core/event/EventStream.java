package com.pi.ai.core.event;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 通用异步事件流基础设施，基于 {@link LinkedBlockingQueue} + {@link CompletableFuture} 实现。
 *
 * <p>该类是 AI 事件驱动的核心基础设施组件，提供了一种生产者-消费者模式的异步事件流处理机制。
 * 生产者通过 {@link #push(Object)} 方法推送事件，消费者通过实现 {@link Iterable} 接口提供的
 * {@link #iterator()} 以阻塞方式逐个获取事件。流结束时通过 {@link #end(Object)} 显式标记，
 * 或当 push 的事件满足构造时指定的 {@code isComplete} 条件时自动完成。
 *
 * <p><b>线程安全：</b>本类设计为完全线程安全。push 方法可以在不同于 iterator 消费的线程中
 * 并发调用，底层采用 {@link LinkedBlockingQueue} 保证 FIFO 顺序和线程安全。
 *
 * <p><b>典型使用场景：</b>适用于 LLM 流式响应、异步任务链、事件驱动编排等场景，作为
 * 响应式编程中背压（backpressure）和异步数据流的简化实现。
 *
 * <p><b>生命周期：</b>
 * <ol>
 *   <li>创建：通过构造器创建实例，指定终止条件和结果提取逻辑</li>
 *   <li>活跃：生产者通过 push 持续推送事件，消费者通过 iterator 阻塞消费</li>
 *   <li>终止：满足终止条件或显式调用 end() 后，流进入终止状态</li>
 * </ol>
 *
 * @param <E> 事件类型，表示流中传递的事件元素
 * @param <R> 最终结果类型，表示流终止时产出的结果
 */
public class EventStream<E, R> implements Iterable<E>, AutoCloseable {

    /**
     * 哨兵值（Sentinel），用于通知所有正在等待的迭代器流已结束。
     * 使用 Object 类型避免泛型擦除问题，在队列中作为特殊标记与普通事件区分。
     * 当迭代器在队列中取到此对象时，表示流已结束，不再有更多事件。
     */
    private static final Object SENTINEL = new Object();

    /**
     * 内部阻塞队列，泛型擦除后统一存储为 Object。
     * 队列中存储的元素包括：普通事件（E 类型）和哨兵值（SENTINEL）。
     * LinkedBlockingQueue 保证 FIFO 顺序和线程安全。
     */
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();

    /**
     * 最终结果的 CompletableFuture，用于异步获取流终止时的结果。
     * 当 isComplete 条件满足的事件被 push 时，或 end(result) 被显式调用时自动完成。
     * 消费者可以通过 {@link #result()} 获取此 Future 并等待结果。
     */
    private final CompletableFuture<R> finalResult = new CompletableFuture<>();

    /**
     * 判断事件是否为终止事件的谓词（Predicate）。
     * 当 push 的事件满足此条件时，流自动标记为已完成，并提取结果。
     */
    private final Predicate<E> isComplete;

    /**
     * 从终止事件中提取最终结果的函数。
     * 当 isComplete 条件满足时，调用此函数从事件中提取 R 类型的结果。
     */
    private final Function<E, R> extractResult;

    /**
     * 流是否已结束的标志，使用 volatile 关键字保证跨线程的可见性。
     * 当 done 为 true 时，push 操作会被静默忽略，不再接受新事件。
     * volatile 确保一个线程修改 done 值后，其他线程能立即看到最新值。
     */
    private volatile boolean done = false;

    /**
     * 创建 EventStream 实例。
     *
     * @param isComplete    判断事件是否为终止事件的谓词。
     *                      当 push 的事件满足此条件时，流自动终止并提取结果。
     * @param extractResult 从终止事件中提取最终结果的函数。
     *                      例如，从结束事件中提取聚合后的消息或状态。
     */
    public EventStream(Predicate<E> isComplete, Function<E, R> extractResult) {
        this.isComplete = isComplete;
        this.extractResult = extractResult;
    }

    /**
     * 生产者推送事件到流中，供消费者通过迭代器异步消费。
     *
     * <p><b>自动终止逻辑：</b>如果事件满足 {@code isComplete} 条件，则自动标记流为已完成，
     * 并通过 {@code extractResult} 从事件中提取最终结果，设置到 {@link #finalResult} 中。
     * 注意：即使流已标记为完成，当前事件仍然会被放入队列供消费者消费。
     *
     * <p><b>幂等性：</b>流结束后再调用 push 是空操作（静默忽略），不会抛出异常，
     * 也不会向队列中添加任何元素。
     *
     * @param event 要推送的事件，不应为 null（null 事件可能导致迭代器处理异常）
     */
    public void push(E event) {
        // 【第一步：先检查 done 标志】快速路径 —— 如果流已结束，直接静默忽略本次 push。
        // 为什么先检查 done？因为 done 是 volatile 变量，检查成本极低，能快速拦截结束后的无效推送，
        // 避免进入 isComplete.test(event) 这种可能较重的判断逻辑，同时也防止结束事件被重复入队。
        if (done) {
            return;
        }
        // 【第二步：再检查 isComplete 条件】判断当前事件是否为终止事件。
        // 为什么放在 done 检查之后？因为 isComplete 是用户自定义的 Predicate，可能包含复杂逻辑，
        // 只有在流确实未结束时才需要执行此判断，避免不必要的计算。
        // 注意：此处存在竞态窗口 —— 两个线程可能同时通过 done 检查，但 LinkedBlockingQueue 保证入队有序，
        // 队列中可能出现多个终止事件，由迭代器侧的哨兵机制保证安全消费。
        if (isComplete.test(event)) {
            // 设置 done = true，阻止后续任何 push 操作（包括当前线程可能的后续 push）
            done = true;
            // 通过 extractResult 从终止事件中提取最终结果，完成 CompletableFuture
            // 如果 finalResult 已被其他线程先完成，complete() 会静默忽略，不会抛出异常
            finalResult.complete(extractResult.apply(event));
        }
        // 【第三步：最后入队】将事件放入阻塞队列，供消费者通过迭代器消费。
        // 为什么入队放在最后？因为入队是无条件的 —— 即使当前事件是终止事件，也应当被消费者看到，
        // 消费者需要消费完队列中所有事件（包括终止事件本身）后才能感知流的结束。
        // 使用 offer 而非 put，因为 LinkedBlockingQueue 无界，offer 总是立即成功。
        queue.offer(event);
    }

    /**
     * 标记流结束并设置最终结果。
     *
     * <p>此方法是幂等的，多次调用不会产生副作用，也不会抛出异常。
     * 调用后会向队列中放入哨兵值（SENTINEL），通知所有正在等待的迭代器流已结束。
     * 如果 finalResult 尚未完成，则通过 complete 方法安全地设置结果值。
     *
     * <p><b>与自动终止的关系：</b>如果流已经通过 isComplete 条件自动终止，
     * 再次调用 end() 不会产生任何副作用，finalResult 不会被重复设置。
     *
     * @param result 最终结果，可以为 null。如果为 null，则仅通知迭代器结束但不设置结果值。
     */
    public void end(R result) {
        // 【第一步：先设 done = true】标记流为已结束状态。
        // 为什么先设 done 再放哨兵？因为 done 是 volatile 变量，设置后对所有线程立即可见，
        // 后续的 push 操作会被快速拦截（见 push 方法第一行），避免在放哨兵和放哨兵之间的
        // 极短时间内有新的 push 将事件插入队列，造成"哨兵之后的幽灵事件"。
        // 这是典型的"先关闸门再通知"策略 —— 先停止接受新事件，再通知消费者结束。
        done = true;
        if (result != null) {
            // 使用 complete 而非 completeExceptionally：
            // 如果 finalResult 已经完成（例如通过自动终止），complete 会静默忽略，不会抛出异常
            finalResult.complete(result);
        }
        // 【第二步：再放哨兵】向队列中放入哨兵值，通知所有正在等待的迭代器流已结束。
        // 为什么放哨兵在 done 设值之后？因为 done 负责阻止新的生产者，哨兵负责通知消费者，
        // 如果先放哨兵再设 done，则哨兵之后的瞬间可能还有事件入队，导致消费者读到哨兵后
        // 以为流已结束，但队列中还有未消费的事件。先设 done 确保了不会有新事件入队，
        // 然后放哨兵，消费者读到哨兵时队列中确实没有后续事件了。
        queue.offer(SENTINEL);
    }

    /**
     * 无结果地标记流结束。
     *
     * <p>仅通知迭代器流已结束，不设置最终结果。
     * 适用于不需要最终结果值的场景，或流提前终止时仅通知消费者停止消费。
     */
    public void end() {
        end(null);
    }

    /**
     * 返回阻塞式迭代器，按推送顺序逐个获取事件。
     *
     * <p>迭代器的 {@code hasNext()} 方法会阻塞等待直到有事件可用或流结束。
     * 底层使用 {@link LinkedBlockingQueue#take()} 方法，在没有可用元素时
     * 线程会进入等待状态，直到有元素被 push 或线程被中断。
     *
     * <p>当收到哨兵值（SENTINEL）时，{@code hasNext()} 返回 false 表示迭代结束。
     * 哨兵值会被放回队列（通过 offer 方法），以便多个迭代器实例都能收到结束信号。
     *
     * @return 阻塞式事件迭代器，支持多线程并发消费
     */
    @Override
    public Iterator<E> iterator() {
        // 每次调用 iterator() 都创建一个新的独立迭代器实例，多个迭代器共享同一个队列。
        // 每个迭代器拥有自己的 next 和 finished 状态，互不干扰。
        // 多迭代器共享机制：通过 SENTINEL 哨兵值实现 —— 每个迭代器读到哨兵后将其放回队列，
        // 使得其他迭代器也能读到它。这保证了所有迭代器都能正确感知流的结束。
        return new Iterator<>() {
            /** 预取的下一个事件，通过 hasNext() 从队列中取出并缓存 */
            private E next = null;
            /** 当前迭代器是否已结束 */
            private boolean finished = false;

            @Override
            public boolean hasNext() {
                // 如果当前迭代器已结束，直接返回 false，避免重复从队列中取元素
                if (finished) {
                    return false;
                }
                try {
                    // 【阻塞等待】从 LinkedBlockingQueue 中 take() 一个元素。
                    // 如果队列为空，当前线程会阻塞直到有元素可用或线程被中断。
                    // 这是最重要的设计决策 —— 使用 take() 而非 poll() 使得事件消费天然是阻塞式的，
                    // 消费者不需要自旋忙等，CPU 利用率高。
                    Object taken = queue.take();
                    // 【检查哨兵值】判断取出的元素是否为 SENTINEL
                    if (taken == SENTINEL) {
                        // 标记当前迭代器为已结束状态
                        finished = true;
                        // 【哨兵值放回队列】关键设计 —— 将哨兵值重新放回队列，以便其他正在等待的
                        // 迭代器也能收到结束信号。这是多迭代器共享的核心机制：
                        // 如果不放回，第一个读到哨兵的迭代器会消费掉它，其他迭代器将永远阻塞在 take() 上。
                        // 使用 offer 而非 put，因为队列容量无界，offer 总是成功
                        queue.offer(SENTINEL);
                        return false;
                    }
                    @SuppressWarnings("unchecked")
                    E event = (E) taken;
                    // 将取出的普通事件缓存到 next 字段，供 next() 方法返回
                    next = event;
                    return true;
                } catch (InterruptedException e) {
                    // 线程被中断时恢复中断状态并结束迭代
                    // 恢复中断状态很重要，因为 JVM 的 InterruptedException 会清除中断标志，
                    // 调用 interrupt() 恢复标志位，让上层调用者能感知到中断发生
                    Thread.currentThread().interrupt();
                    finished = true;
                    return false;
                }
            }

            @Override
            public E next() {
                // 如果迭代器已结束或 next 为空（即没有调用 hasNext 或 hasNext 返回 false 后未调用 next），
                // 抛出 NoSuchElementException，遵循标准 Iterator 契约
                if (finished || next == null) {
                    throw new NoSuchElementException("流中已无更多事件");
                }
                // 返回缓存的 next 值，并将 next 置为 null，防止重复返回同一个事件
                E result = next;
                next = null;
                return result;
            }
        };
    }

    /**
     * 获取最终结果的 Future。
     *
     * <p>当 isComplete 事件被 push 或 end(result) 被调用时，此 Future 完成。
     * 消费者可以通过 {@link CompletableFuture#join()} 或 {@link CompletableFuture#get()}
     * 阻塞等待最终结果，也可以通过 thenApply/thenAccept 等回调方式异步处理。
     *
     * @return 最终结果的 CompletableFuture，可能为未完成状态
     */
    public CompletableFuture<R> result() {
        return finalResult;
    }

    /**
     * 关闭流。如果流尚未结束，调用 {@link #end()} 标记结束。
     *
     * <p>实现 {@link AutoCloseable} 接口，支持 try-with-resources 语法，
     * 确保流在使用完毕后能正确关闭，防止资源泄漏。
     * 此方法是幂等的，多次调用或流已结束时调用均安全。
     */
    @Override
    public void close() {
        // 只检查 done 标志，不检查 finalResult 的状态，也不检查队列是否为空。
        // 为什么只检查 done？因为 done 是 volatile 的"门闸"标志，是最轻量、最可靠的判断方式。
        // 不检查 finalResult.isDone()：因为 finalResult 可能通过 push 中的自动终止逻辑完成，
        // 但此时 done 已经为 true 了，检查 finalResult 会引入不必要的间接性。
        // 不检查队列是否为空：close() 是"停止接受新事件+通知消费者"，而不是"清空队列"，
        // 队列中已有的未消费事件仍由消费者决定如何处理。
        if (!done) {
            // 调用 end() 会先设 done=true，再放哨兵 —— 见 end() 方法的注释说明
            end();
        }
    }
}