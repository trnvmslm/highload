package kz.highload.polls.benchmark;

public record LatencyRow(String protocol, Operation operation, int threads, LatencyStats stats) {
}
