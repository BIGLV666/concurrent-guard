package io.github.biglv666.guard.lock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于业务 key 管理 JVM 本地锁。
 *
 * <p>锁槽位采用引用计数，业务 key 执行结束后会主动清理，避免动态 key 导致锁表无限增长。
 * 不使用 {@code String.intern()}，避免污染 JVM 字符串池。</p>
 */
public class LocalLockManager {

    private final ConcurrentHashMap<String, Entry> locks = new ConcurrentHashMap<>();

    /**
     * 获取指定 key 的锁槽位并增加使用引用。
     *
     * @param key 完整业务锁键
     * @return 锁句柄
     */
    public Handle acquire(String key) {
        Entry entry = locks.compute(key, (ignored, current) -> {
            if (current == null) {
                current = new Entry();
            }
            current.references.incrementAndGet();
            return current;
        });
        return new Handle(key, entry);
    }

    /** 当前锁槽位数量，主要用于监控和测试。 */
    int size() {
        return locks.size();
    }

    /** 一次本地锁使用的句柄。 */
    public final class Handle {
        private final String key;
        private final Entry entry;
        private boolean released;

        private Handle(String key, Entry entry) {
            this.key = key;
            this.entry = entry;
        }

        public ReentrantLock lock() {
            return entry.lock;
        }

        /** 释放引用并在没有竞争者时清理锁槽位。 */
        public void close() {
            if (released) {
                return;
            }
            released = true;
            if (entry.references.decrementAndGet() == 0) {
                locks.computeIfPresent(key, (ignored, current) ->
                        current == entry && current.references.get() == 0
                                && !current.lock.isLocked() && !current.lock.hasQueuedThreads()
                                ? null : current);
            }
        }
    }

    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger references = new AtomicInteger();
    }
}
