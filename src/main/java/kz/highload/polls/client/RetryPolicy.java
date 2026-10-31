package kz.highload.polls.client;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, double backoffMultiplier) {
    private static final int DEFAULT_MAX_ATTEMPTS = 4;
    private static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(100);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(1);
    private static final double DEFAULT_MULTIPLIER = 2.0;

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_BACKOFF, DEFAULT_MAX_BACKOFF, DEFAULT_MULTIPLIER);
    }

    public Duration backoffBeforeRetry(int retryNumber) {
        double exponentialNanos = initialBackoff.toNanos() * Math.pow(backoffMultiplier, retryNumber - 1);
        long cappedNanos = (long) Math.min(exponentialNanos, maxBackoff.toNanos());
        long jitteredNanos = ThreadLocalRandom.current().nextLong(cappedNanos + 1);
        return Duration.ofNanos(jitteredNanos);
    }
}
