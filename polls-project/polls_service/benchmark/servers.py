import multiprocessing
import socket

from polls_service.fault import FaultInjector
from polls_service.grpc_server import GrpcServer
from polls_service.rest_server import RestServer
from polls_service.service import PollService

HOST = "127.0.0.1"
STARTUP_TIMEOUT_S = 30
SHUTDOWN_TIMEOUT_S = 10


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind((HOST, 0))
        return probe.getsockname()[1]


def run_servers(grpc_port: int, rest_port: int, failure_rate: float, ready, stop) -> None:
    poll_service = PollService()
    fault_injector = FaultInjector(failure_rate)
    with GrpcServer(grpc_port, poll_service, fault_injector), RestServer(rest_port, poll_service, fault_injector):
        ready.set()
        stop.wait()


class ServerProcess:
    def __init__(self, failure_rate: float):
        self.grpc_port = free_port()
        self.rest_port = free_port()
        context = multiprocessing.get_context("spawn")
        self._ready = context.Event()
        self._stop = context.Event()
        arguments = (self.grpc_port, self.rest_port, failure_rate, self._ready, self._stop)
        self._process = context.Process(target=run_servers, args=arguments, daemon=True)

    def __enter__(self) -> "ServerProcess":
        self._process.start()
        if not self._ready.wait(STARTUP_TIMEOUT_S):
            self._process.terminate()
            raise RuntimeError("Servers did not start")
        return self

    def __exit__(self, *exc_info) -> None:
        self._stop.set()
        self._process.join(SHUTDOWN_TIMEOUT_S)
