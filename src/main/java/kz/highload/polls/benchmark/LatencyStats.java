package kz.highload.polls.benchmark;

import java.util.Arrays;

public record LatencyStats(int requests, int errors, double p50Ms, double p95Ms, double p99Ms,
                           double meanMs, double maxMs, double throughputRps) {
    private static final double NANOS_PER_MILLI = 1_000_000.0;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    public static LatencyStats of(long[] latencyNanos, boolean[] failed, long elapsedNanos) {
        long[] successful = successfulLatencies(latencyNanos, failed);
        Arrays.sort(successful);
        int errors = latencyNanos.length - successful.length;
        if (successful.length == 0) {
            return new LatencyStats(latencyNanos.length, errors, 0, 0, 0, 0, 0, 0);
        }
        double mean = Arrays.stream(successful).average().orElse(0) / NANOS_PER_MILLI;
        double max = successful[successful.length - 1] / NANOS_PER_MILLI;
        double throughput = successful.length / (elapsedNanos / NANOS_PER_SECOND);
        return new LatencyStats(latencyNanos.length, errors,
                percentile(successful, 50), percentile(successful, 95), percentile(successful, 99),
                mean, max, throughput);
    }

    private static long[] successfulLatencies(long[] latencyNanos, boolean[] failed) {
        long[] buffer = new long[latencyNanos.length];
        int count = 0;
        for (int index = 0; index < latencyNanos.length; index++) {
            if (!failed[index]) {
                buffer[count] = latencyNanos[index];
                count++;
            }
        }
        return Arrays.copyOf(buffer, count);
    }

    private static double percentile(long[] sortedNanos, int percent) {
        int rank = (int) Math.ceil(percent / 100.0 * sortedNanos.length);
        int index = Math.max(rank - 1, 0);
        return sortedNanos[index] / NANOS_PER_MILLI;
    }
}
