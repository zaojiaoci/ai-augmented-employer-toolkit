package io.github.aiaugmentedemployertoolkit.service;

/**
 * 请求被限流拒绝。单独成类，便于 Controller 映射为 HTTP 429。
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
