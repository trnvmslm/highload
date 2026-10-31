package kz.highload.polls.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RetryExecutorTest {
    private static final RetryPolicy FAST_POLICY =
            new RetryPolicy(4, Duration.ofMillis(1), Duration.ofMillis(5), 2.0);

    @Test
    void retriesUntilSuccess() {
        RetryExecutor executor = new RetryExecutor(FAST_POLICY);
        AtomicInteger calls = new AtomicInteger();

        String result = executor.execute(() -> failTwiceThenSucceed(calls));

        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        RetryExecutor executor = new RetryExecutor(FAST_POLICY);
        AtomicInteger calls = new AtomicInteger();

        assertThrows(PollClientException.class, () -> executor.execute(() -> alwaysFail(calls)));
        assertEquals(4, calls.get());
    }

    @Test
    void backoffNeverExceedsMaximum() {
        RetryPolicy policy = RetryPolicy.defaults();

        for (int retry = 1; retry <= 10; retry++) {
            Duration backoff = policy.backoffBeforeRetry(retry);
            assertTrue(backoff.compareTo(policy.maxBackoff()) <= 0);
        }
    }

    private static String failTwiceThenSucceed(AtomicInteger calls) throws IOException {
        if (calls.incrementAndGet() <= 2) {
            throw new RetryableStatusException(503);
        }
        return "ok";
    }

    private static String alwaysFail(AtomicInteger calls) throws IOException {
        calls.incrementAndGet();
        throw new IOException("connection refused");
    }
}
