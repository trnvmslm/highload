package kz.highload.polls.grpc;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import kz.highload.polls.domain.PollService;
import kz.highload.polls.fault.FaultInjector;

public final class GrpcServer implements AutoCloseable {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final Server server;

    public GrpcServer(int port, PollService pollService, FaultInjector faultInjector) {
        PollGrpcService service = new PollGrpcService(pollService);
        FaultInjectionInterceptor interceptor = new FaultInjectionInterceptor(faultInjector);
        this.server = ServerBuilder.forPort(port)
                .addService(ServerInterceptors.intercept(service, interceptor))
                .build();
    }

    public void start() throws IOException {
        server.start();
    }

    public int port() {
        return server.getPort();
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    @Override
    public void close() throws InterruptedException {
        server.shutdown();
        if (!server.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            server.shutdownNow();
        }
    }
}
