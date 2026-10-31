package kz.highload.polls.rest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;
import kz.highload.polls.domain.PollService;
import kz.highload.polls.fault.FaultInjector;

public final class RestServer implements AutoCloseable {
    private static final int WORKER_THREADS = 16;
    private static final int BACKLOG = 0;
    private static final int STOP_DELAY_SECONDS = 0;

    private final HttpServer server;
    private final ExecutorService workers;

    public RestServer(int port, PollService pollService, FaultInjector faultInjector) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), BACKLOG);
        this.workers = Executors.newFixedThreadPool(WORKER_THREADS);
        server.createContext("/v1/polls", new PollHttpHandler(pollService, faultInjector));
        server.setExecutor(workers);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(STOP_DELAY_SECONDS);
        workers.shutdownNow();
    }
}
