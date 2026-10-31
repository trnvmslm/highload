import math
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Callable

NANOS_PER_MILLI = 1_000_000
NANOS_PER_SECOND = 1_000_000_000


@dataclass(frozen=True)
class LatencyStats:
    requests: int
    errors: int
    p50_ms: float
    p95_ms: float
    p99_ms: float
    mean_ms: float
    max_ms: float
    throughput_rps: float

    @classmethod
    def from_samples(cls, latencies_ns: list[int], failed: list[bool], elapsed_ns: int) -> "LatencyStats":
        successful = sorted(latency for latency, is_failed in zip(latencies_ns, failed) if not is_failed)
        errors = len(latencies_ns) - len(successful)
        if not successful:
            return cls(len(latencies_ns), errors, 0, 0, 0, 0, 0, 0)
        return cls(
            requests=len(latencies_ns),
            errors=errors,
            p50_ms=_percentile(successful, 50),
            p95_ms=_percentile(successful, 95),
            p99_ms=_percentile(successful, 99),
            mean_ms=sum(successful) / len(successful) / NANOS_PER_MILLI,
            max_ms=successful[-1] / NANOS_PER_MILLI,
            throughput_rps=len(successful) / (elapsed_ns / NANOS_PER_SECOND),
        )


def _percentile(sorted_ns: list[int], percent: int) -> float:
    rank = math.ceil(percent / 100 * len(sorted_ns))
    return sorted_ns[max(rank - 1, 0)] / NANOS_PER_MILLI


class RequestCounter:
    def __init__(self, total: int):
        self._total = total
        self._next = 0
        self._lock = threading.Lock()

    def take(self) -> int | None:
        with self._lock:
            if self._next >= self._total:
                return None
            index = self._next
            self._next += 1
            return index


class LoadRunner:
    def run(self, requests: int, threads: int, action: Callable[[int], None]) -> LatencyStats:
        latencies_ns = [0] * requests
        failed = [False] * requests
        counter = RequestCounter(requests)
        started_at = time.perf_counter_ns()
        with ThreadPoolExecutor(max_workers=threads) as workers:
            for _ in range(threads):
                workers.submit(self._run_requests, action, counter, latencies_ns, failed)
        elapsed_ns = time.perf_counter_ns() - started_at
        return LatencyStats.from_samples(latencies_ns, failed, elapsed_ns)

    @staticmethod
    def _run_requests(action, counter: RequestCounter, latencies_ns: list[int], failed: list[bool]) -> None:
        index = counter.take()
        while index is not None:
            started_at = time.perf_counter_ns()
            try:
                action(index)
            except Exception:
                failed[index] = True
            latencies_ns[index] = time.perf_counter_ns() - started_at
            index = counter.take()
