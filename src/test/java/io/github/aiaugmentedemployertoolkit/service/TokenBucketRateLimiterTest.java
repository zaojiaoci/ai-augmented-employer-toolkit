package io.github.aiaugmentedemployertoolkit.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketRateLimiterTest {

    @Test
    void consumesCapacityThenRejects() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 0.1, 100);

        assertTrue(limiter.tryAcquire("k"));
        assertTrue(limiter.tryAcquire("k"));
        assertFalse(limiter.tryAcquire("k"), "容量耗尽后应拒绝");
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        // 每分钟补充 600000 个 ≈ 每秒 10000 个，20ms 足够补充
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 600000, 100);

        assertTrue(limiter.tryAcquire("k"));
        assertFalse(limiter.tryAcquire("k"));

        Thread.sleep(30);

        assertTrue(limiter.tryAcquire("k"), "经过补充后应再次放行");
    }

    @Test
    void tracksKeysIndependently() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1, 0.1, 100);

        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("b"), "不同 key 互不影响");

        assertEquals(2, limiter.trackedKeys());
    }

    @Test
    void availableTokensStartsAtCapacityAndDecreases() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, 0.1, 100);

        assertEquals(3.0, limiter.availableTokens("k"), 0.001);
        limiter.tryAcquire("k");
        assertEquals(2.0, limiter.availableTokens("k"), 0.001);
    }

    @Test
    void rejectsNullKey() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1, 100);
        assertFalse(limiter.tryAcquire(null));
    }

    @Test
    void supportsMultiPermitAcquisition() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 0.1, 100);

        assertTrue(limiter.tryAcquire("k", 3));
        assertFalse(limiter.tryAcquire("k", 3), "剩余 2 个令牌不足 3 个");
    }
}
