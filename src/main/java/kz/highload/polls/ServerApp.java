package kz.highload.polls;

import java.time.Clock;

import kz.highload.polls.domain.PollService;
import kz.highload.polls.fault.FaultInjector;
import kz.highload.polls.grpc.GrpcServer;
import kz.highload.polls.rest.RestServer;

public final class ServerApp {
    private static final int GRPC_PORT = 50051;
    private static final int REST_PORT = 8080;

    private ServerApp() {
    }

    public static void main(String[] args) throws Exception {
        PollService pollService = new PollService(Clock.systemUTC());
        FaultInjector faultInjector = FaultInjector.disabled();

        try (GrpcServer grpcServer = new GrpcServer(GRPC_PORT, pollService, faultInjector);
             RestServer restServer = new RestServer(REST_PORT, pollService, faultInjector)) {
            grpcServer.start();
            restServer.start();
            System.out.println("gRPC server listening on port " + grpcServer.port());
            System.out.println("REST server listening on port " + restServer.port());
            grpcServer.awaitTermination();
        }
    }
}
