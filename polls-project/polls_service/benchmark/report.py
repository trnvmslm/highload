import csv
import os
import platform
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from polls_service.benchmark.payload import PayloadComparison
from polls_service.benchmark.runner import LatencyStats
from polls_service.benchmark.workload import Operation


@dataclass(frozen=True)
class LatencyRow:
    protocol: str
    operation: Operation
    threads: int
    stats: LatencyStats


def to_markdown(requests: int, warmup: int, failure_rate: float,
                latency_rows: list[LatencyRow], payload_rows: list[PayloadComparison]) -> str:
    lines = [
        "# gRPC vs REST/JSON benchmark",
        "",
        f"- Run at: {datetime.now().isoformat(timespec='seconds')}",
        f"- Python: {platform.python_version()}, CPU cores: {os.cpu_count()}",
        f"- Requests per run: {requests}, warm-up: {warmup}",
        f"- Injected failure rate: {failure_rate * 100:.1f} %",
        "",
        "## Latency",
        "",
        "| Protocol | Operation | Threads | p50 ms | p95 ms | p99 ms | mean ms | max ms | Throughput rps | Errors |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for row in latency_rows:
        stats = row.stats
        lines.append(
            f"| {row.protocol} | {row.operation.value} | {row.threads} | {stats.p50_ms:.3f} | {stats.p95_ms:.3f} "
            f"| {stats.p99_ms:.3f} | {stats.mean_ms:.3f} | {stats.max_ms:.3f} | {stats.throughput_rps:.0f} "
            f"| {stats.errors} |"
        )
    lines += [
        "",
        "## Payload size (body bytes)",
        "",
        "| Operation | gRPC request | REST request | gRPC response | REST response | Response saved |",
        "|---|---|---|---|---|---|",
    ]
    for row in payload_rows:
        lines.append(
            f"| {row.operation.value} | {row.grpc_request_bytes} | {row.rest_request_bytes} "
            f"| {row.grpc_response_bytes} | {row.rest_response_bytes} | {row.response_saving_percent:.0f} % |"
        )
    return "\n".join(lines) + "\n"


def write_files(output_dir: Path, markdown: str,
                latency_rows: list[LatencyRow], payload_rows: list[PayloadComparison]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "benchmark.md").write_text(markdown, encoding="utf-8")
    with open(output_dir / "latency.csv", "w", newline="", encoding="utf-8") as latency_file:
        writer = csv.writer(latency_file)
        writer.writerow(["protocol", "operation", "threads", "requests", "errors", "p50_ms", "p95_ms",
                         "p99_ms", "mean_ms", "max_ms", "throughput_rps"])
        for row in latency_rows:
            stats = row.stats
            writer.writerow([row.protocol, row.operation.value, row.threads, stats.requests, stats.errors,
                             f"{stats.p50_ms:.3f}", f"{stats.p95_ms:.3f}", f"{stats.p99_ms:.3f}",
                             f"{stats.mean_ms:.3f}", f"{stats.max_ms:.3f}", f"{stats.throughput_rps:.0f}"])
    with open(output_dir / "payload.csv", "w", newline="", encoding="utf-8") as payload_file:
        writer = csv.writer(payload_file)
        writer.writerow(["operation", "grpc_request_bytes", "rest_request_bytes",
                         "grpc_response_bytes", "rest_response_bytes"])
        for row in payload_rows:
            writer.writerow([row.operation.value, row.grpc_request_bytes, row.rest_request_bytes,
                             row.grpc_response_bytes, row.rest_response_bytes])
