from polls_service.fault import FaultInjector
from polls_service.grpc_server import GrpcServer
from polls_service.rest_server import RestServer
from polls_service.service import PollService

GRPC_PORT = 50051
REST_PORT = 8080


def main() -> None:
    poll_service = PollService()
    fault_injector = FaultInjector()
    with GrpcServer(GRPC_PORT, poll_service, fault_injector) as grpc_server, \
            RestServer(REST_PORT, poll_service, fault_injector) as rest_server:
        print(f"gRPC server listening on port {grpc_server.port}")
        print(f"REST server listening on port {rest_server.port}")
        grpc_server.wait_for_termination()


if __name__ == "__main__":
    main()
