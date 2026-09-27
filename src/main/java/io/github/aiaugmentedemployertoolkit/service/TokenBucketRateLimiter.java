package io.github.aiaugmentedemployertoolkit.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌桶限流，按 key 独立计数。
 * <p>
 * 不引入额外依赖（如 Bucket4j / Resilience4j），
 * 用最小实现覆盖 demo 场景：全局维度的突发容量 + 匀速补充。
 */
@Component
public class TokenBucketRateLimiter {

    private final int capacity;

    /** 每分钟补充的令牌数 */
    private final double refillPerMinute;

    /** 最大追踪 key 数，防止 key 无限增长 */
    private final int maxTrackedKeys;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(
            @Value("${app.rate-limit.capacity:20}") int capacity,
            @Value("${app.rate-limit.refill-per-minute:10}") double refillPerMinute,
            @Value("${app.rate-limit.max-tracked-keys:10000}") int maxTrackedKeys) {
        this.capacity = Math.max(1, capacity);
        this.refillPerMinute = Math.max(0.1, refillPerMinute);
        this.maxTrackedKeys = Math.max(1, maxTrackedKeys);
    }

    public boolean tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    public boolean tryAcquire(String key, int permits) {
        if (key == null) {
            return false;
        }
        if (buckets.size() > maxTrackedKeys) {
            evictFullBuckets();
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        synchronized (bucket) {
            bucket.refill(capacity, refillPerMinute);
            if (bucket.tokens >= permits) {
                bucket.tokens -= permits;
                return true;
            }
            return false;
        }
    }

    /**
     * 仅用于测试与外部观测：当前 key 的剩余令牌数（会先按时间补充）。
     */
    public double availableTokens(String key) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            return capacity;
        }
        synchronized (bucket) {
            bucket.refill(capacity, refillPerMinute);
            return bucket.tokens;
        }
    }

    public int trackedKeys() {
        return buckets.size();
    }

    int capacity() {
        return capacity;
    }

    private void evictFullBuckets() {
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            Bucket bucket = entry.getValue();
            synchronized (bucket) {
                if (bucket.tokens >= capacity) {
                    buckets.remove(entry.getKey(), bucket);
                }
            }
        }
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos = System.nanoTime();

        private Bucket(double initialTokens) {
            this.tokens = initialTokens;
        }

        private void refill(int capacity, double refillPerMinute) {
            long now = System.nanoTime();
            long elapsedNanos = now - lastRefillNanos;
            if (elapsedNanos <= 0) {
                return;
            }
            double elapsedMinutes = elapsedNanos / 60_000_000_000.0;
            tokens = Math.min(capacity, tokens + elapsedMinutes * refillPerMinute);
            lastRefillNanos = now;
        }
    }
}
