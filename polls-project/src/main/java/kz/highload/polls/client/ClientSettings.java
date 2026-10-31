package kz.highload.polls.client;

import java.time.Duration;

public record ClientSettings(Duration timeout, RetryPolicy retryPolicy) {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    public static ClientSettings defaults() {
        return new ClientSettings(DEFAULT_TIMEOUT, RetryPolicy.defaults());
    }
}
