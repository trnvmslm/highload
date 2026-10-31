package kz.highload.polls.client;

import java.io.IOException;
import java.time.Duration;

public class RetryExecutor {
    private final RetryPolicy retryPolicy;

    public RetryExecutor(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
    }

    public <T> T execute(RetryableCall<T> call) {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            if (attempt > 1) {
                sleep(retryPolicy.backoffBeforeRetry(attempt - 1));
            }
            try {
                return call.attempt();
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        throw new PollClientException("Gave up after " + retryPolicy.maxAttempts() + " attempts", lastFailure);
    }

    private static void sleep(Duration backoff) {
        try {
            Thread.sleep(backoff.toMillis(), backoff.toNanosPart() % 1_000_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PollClientException("Interrupted while waiting to retry", e);
        }
    }
}
