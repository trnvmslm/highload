package kz.highload.polls.benchmark;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadRunner {
    private static final long MAX_RUN_MINUTES = 30;

    public LatencyStats run(int requests, int threads, RequestAction action) throws InterruptedException {
        long[] latencyNanos = new long[requests];
        boolean[] failed = new boolean[requests];
        AtomicInteger nextRequest = new AtomicInteger();

        ExecutorService workers = Executors.newFixedThreadPool(threads);
        long startedAt = System.nanoTime();
        for (int worker = 0; worker < threads; worker++) {
            workers.execute(() -> runRequests(action, nextRequest, latencyNanos, failed));
        }
        workers.shutdown();
        if (!workers.awaitTermination(MAX_RUN_MINUTES, TimeUnit.MINUTES)) {
            workers.shutdownNow();
            throw new IllegalStateException("Benchmark run did not finish in " + MAX_RUN_MINUTES + " minutes");
        }
        long elapsedNanos = System.nanoTime() - startedAt;
        return LatencyStats.of(latencyNanos, failed, elapsedNanos);
    }

    private static void runRequests(RequestAction action, AtomicInteger nextRequest,
                                    long[] latencyNanos, boolean[] failed) {
        int index = nextRequest.getAndIncrement();
        while (index < latencyNanos.length) {
            long startedAt = System.nanoTime();
            try {
                action.run(index);
            } catch (RuntimeException e) {
                failed[index] = true;
            }
            latencyNanos[index] = System.nanoTime() - startedAt;
            index = nextRequest.getAndIncrement();
        }
    }
}
