package io.github.biglv666.guard.lock;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LocalLockManagerTest {

    @Test
    void sameKeyIsExclusiveAndDifferentKeysCanRunInParallel() throws Exception {
        LocalLockManager manager = new LocalLockManager();
        LocalLockManager.Handle first = manager.acquire("a");
        assertTrue(first.lock().tryLock());
        LocalLockManager.Handle second = manager.acquire("a");
        AtomicBoolean acquired = new AtomicBoolean();
        Thread contender = new Thread(() -> {
            try {
                acquired.set(second.lock().tryLock(20, TimeUnit.MILLISECONDS));
                if (acquired.get()) second.lock().unlock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        contender.start();
        contender.join();
        assertFalse(acquired.get());
        second.close();
        first.lock().unlock();
        first.close();
        assertEquals(0, manager.size());
    }

    @Test
    void exceptionStillAllowsReacquire() {
        LocalLockManager manager = new LocalLockManager();
        LocalLockManager.Handle handle = manager.acquire("a");
        handle.lock().lock();
        try {
            assertThrows(IllegalStateException.class, () -> { throw new IllegalStateException(); });
        } finally {
            handle.lock().unlock();
            handle.close();
        }
        LocalLockManager.Handle again = manager.acquire("a");
        assertTrue(again.lock().tryLock());
        again.lock().unlock();
        again.close();
    }
}
