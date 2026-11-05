package io.github.biglv666.guard.idempotent;

/**
 * 幂等键格式错误：表示业务 key 的运行时值无法安全用于当前存储策略。
 *
 * <p>典型场景：Redis Cluster 下 key 含空 hash-tag（{@code foo{}bar}）或不配对花括号，
 * 无法保证主键与结果哨兵键散列到同一 slot。该异常属于数据/配置错误，
 * <b>不受 {@code guard.idempotent.fail-open} 影响</b>，必须快速失败，
 * 不能以“Redis 故障降级”为名静默跳过幂等防护。</p>
 *
 * @author Guard Team
 * @since 0.3.0
 */
public class IdempotentKeyException extends IllegalArgumentException {

    public IdempotentKeyException(String message) {
        super(message);
    }

    public IdempotentKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
