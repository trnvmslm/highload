package kz.highload.polls.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public class ReportWriter {
    private static final String REPORT_FILE = "benchmark.md";
    private static final String LATENCY_CSV = "latency.csv";
    private static final String PAYLOAD_CSV = "payload.csv";

    public String toMarkdown(BenchmarkConfig config, List<LatencyRow> latencyRows, List<PayloadComparison> payloadRows) {
        StringBuilder report = new StringBuilder();
        appendHeader(report, config);
        appendLatencyTable(report, latencyRows);
        appendPayloadTable(report, payloadRows);
        return report.toString();
    }

    public void writeFiles(Path outputDir, String markdown, List<LatencyRow> latencyRows,
                           List<PayloadComparison> payloadRows) throws IOException {
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve(REPORT_FILE), markdown, StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve(LATENCY_CSV), latencyCsv(latencyRows), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve(PAYLOAD_CSV), payloadCsv(payloadRows), StandardCharsets.UTF_8);
    }

    private static void appendHeader(StringBuilder report, BenchmarkConfig config) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        report.append("# gRPC vs REST/JSON benchmark\n\n")
                .append("- Run at: ").append(timestamp).append('\n')
                .append("- JVM: ").append(System.getProperty("java.vm.name")).append(' ')
                .append(System.getProperty("java.version")).append('\n')
                .append("- CPU cores: ").append(Runtime.getRuntime().availableProcessors()).append('\n')
                .append("- Requests per run: ").append(config.requests())
                .append(", warm-up: ").append(config.warmup()).append('\n')
                .append("- Injected failure rate: ").append(format(config.failureRate() * 100)).append(" %\n\n");
    }

    private static void appendLatencyTable(StringBuilder report, List<LatencyRow> rows) {
        report.append("## Latency\n\n")
                .append("| Protocol | Operation | Threads | p50 ms | p95 ms | p99 ms | mean ms | max ms ")
                .append("| Throughput rps | Errors |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (LatencyRow row : rows) {
            LatencyStats stats = row.stats();
            report.append("| ").append(row.protocol())
                    .append(" | ").append(row.operation().displayName())
                    .append(" | ").append(row.threads())
                    .append(" | ").append(format(stats.p50Ms()))
                    .append(" | ").append(format(stats.p95Ms()))
                    .append(" | ").append(format(stats.p99Ms()))
                    .append(" | ").append(format(stats.meanMs()))
                    .append(" | ").append(format(stats.maxMs()))
                    .append(" | ").append(String.format(Locale.ROOT, "%.0f", stats.throughputRps()))
                    .append(" | ").append(stats.errors())
                    .append(" |\n");
        }
        report.append('\n');
    }

    private static void appendPayloadTable(StringBuilder report, List<PayloadComparison> rows) {
        report.append("## Payload size (body bytes)\n\n")
                .append("| Operation | gRPC request | REST request | gRPC response | REST response ")
                .append("| Request saved | Response saved |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (PayloadComparison row : rows) {
            report.append("| ").append(row.operation().displayName())
                    .append(" | ").append(row.grpcRequestBytes())
                    .append(" | ").append(row.restRequestBytes())
                    .append(" | ").append(row.grpcResponseBytes())
                    .append(" | ").append(row.restResponseBytes())
                    .append(" | ").append(format(row.requestSavingPercent())).append(" %")
                    .append(" | ").append(format(row.responseSavingPercent())).append(" %")
                    .append(" |\n");
        }
        report.append("\ngRPC sizes are Protobuf bodies; each gRPC message also has a 5-byte length prefix. ")
                .append("HTTP headers are not included (HTTP/2 compresses them with HPACK, HTTP/1.1 sends them as text).\n");
    }

    private static String latencyCsv(List<LatencyRow> rows) {
        StringBuilder csv = new StringBuilder(
                "protocol,operation,threads,requests,errors,p50_ms,p95_ms,p99_ms,mean_ms,max_ms,throughput_rps\n");
        for (LatencyRow row : rows) {
            LatencyStats stats = row.stats();
            csv.append(String.join(",",
                    row.protocol(),
                    row.operation().displayName(),
                    String.valueOf(row.threads()),
                    String.valueOf(stats.requests()),
                    String.valueOf(stats.errors()),
                    format(stats.p50Ms()),
                    format(stats.p95Ms()),
                    format(stats.p99Ms()),
                    format(stats.meanMs()),
                    format(stats.maxMs()),
                    format(stats.throughputRps())))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String payloadCsv(List<PayloadComparison> rows) {
        StringBuilder csv = new StringBuilder(
                "operation,grpc_request_bytes,rest_request_bytes,grpc_response_bytes,rest_response_bytes\n");
        for (PayloadComparison row : rows) {
            csv.append(String.join(",",
                    row.operation().displayName(),
                    String.valueOf(row.grpcRequestBytes()),
                    String.valueOf(row.restRequestBytes()),
                    String.valueOf(row.grpcResponseBytes()),
                    String.valueOf(row.restResponseBytes())))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
