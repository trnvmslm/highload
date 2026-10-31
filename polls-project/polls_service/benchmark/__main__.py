import argparse
from pathlib import Path

from polls_service.benchmark.payload import PayloadComparison, PayloadMeter
from polls_service.benchmark.report import LatencyRow, to_markdown, write_files
from polls_service.benchmark.runner import LoadRunner
from polls_service.benchmark.servers import HOST, ServerProcess
from polls_service.benchmark.workload import Operation, Workload, sample_poll
from polls_service.grpc_client import GrpcPollClient
from polls_service.rest_client import RestPollClient


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="gRPC vs REST/JSON benchmark")
    parser.add_argument("--requests", type=int, default=2000)
    parser.add_argument("--warmup", type=int, default=500)
    parser.add_argument("--threads", default="1,16")
    parser.add_argument("--failure-rate", type=float, default=0.0)
    parser.add_argument("--output", type=Path, default=Path("results"))
    return parser.parse_args()


def measure_latency(arguments: argparse.Namespace, grpc_port: int, rest_port: int) -> list[LatencyRow]:
    thread_counts = [int(value) for value in arguments.threads.split(",")]
    runner = LoadRunner()
    clients = [GrpcPollClient(HOST, grpc_port), RestPollClient(HOST, rest_port)]
    rows = []
    for threads in thread_counts:
        for client in clients:
            print(f"Measuring {client.protocol_name} with {threads} thread(s)...")
            workload = Workload(client)
            for operation in Operation:
                action = workload.action_for(operation)
                runner.run(arguments.warmup, threads, action)
                stats = runner.run(arguments.requests, threads, action)
                rows.append(LatencyRow(client.protocol_name, operation, threads, stats))
    for client in clients:
        client.close()
    return rows


def measure_payload() -> list[PayloadComparison]:
    with ServerProcess(failure_rate=0.0) as servers:
        meter = PayloadMeter(HOST, servers.grpc_port, servers.rest_port)
        rows = meter.measure(sample_poll())
        meter.close()
        return rows


def main() -> None:
    arguments = parse_arguments()
    with ServerProcess(arguments.failure_rate) as servers:
        latency_rows = measure_latency(arguments, servers.grpc_port, servers.rest_port)
    payload_rows = measure_payload()
    markdown = to_markdown(arguments.requests, arguments.warmup, arguments.failure_rate, latency_rows, payload_rows)
    write_files(arguments.output, markdown, latency_rows, payload_rows)
    print(markdown)
    print(f"Report written to {arguments.output.resolve()}")


if __name__ == "__main__":
    main()
