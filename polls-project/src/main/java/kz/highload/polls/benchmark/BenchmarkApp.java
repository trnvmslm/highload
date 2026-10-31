package kz.highload.polls.benchmark;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import kz.highload.polls.client.ClientSettings;
import kz.highload.polls.client.GrpcPollClient;
import kz.highload.polls.client.PollClient;
import kz.highload.polls.client.RestPollClient;
import kz.highload.polls.domain.PollService;
import kz.highload.polls.fault.FaultInjector;
import kz.highload.polls.grpc.GrpcServer;
import kz.highload.polls.rest.RestServer;

public final class BenchmarkApp {
    private static final String HOST = "localhost";
    private static final int ANY_FREE_PORT = 0;

    private BenchmarkApp() {
    }

    public static void main(String[] args) throws Exception {
        BenchmarkConfig config = BenchmarkConfig.fromArgs(args);
        PollService pollService = new PollService(Clock.systemUTC());
        FaultInjector faultInjector = new FaultInjector(config.failureRate());
        List<LatencyRow> latencyRows;

        try (GrpcServer grpcServer = new GrpcServer(ANY_FREE_PORT, pollService, faultInjector);
             RestServer restServer = new RestServer(ANY_FREE_PORT, pollService, faultInjector)) {
            grpcServer.start();
            restServer.start();

            latencyRows = measureLatency(config, grpcServer.port(), restServer.port());
        }
        List<PayloadComparison> payloadRows = measurePayload();

        ReportWriter reportWriter = new ReportWriter();
        String markdown = reportWriter.toMarkdown(config, latencyRows, payloadRows);
        reportWriter.writeFiles(config.outputDir(), markdown, latencyRows, payloadRows);
        System.out.println(markdown);
        System.out.println("Report written to " + config.outputDir().toAbsolutePath());
    }

    private static List<LatencyRow> measureLatency(BenchmarkConfig config, int grpcPort, int restPort)
            throws InterruptedException {
        ClientSettings settings = ClientSettings.defaults();
        LoadRunner loadRunner = new LoadRunner();
        List<LatencyRow> rows = new ArrayList<>();

        try (PollClient grpcClient = new GrpcPollClient(HOST, grpcPort, settings);
             PollClient restClient = new RestPollClient(HOST, restPort, settings)) {
            List<PollClient> clients = List.of(grpcClient, restClient);
            for (int threads : config.threadCounts()) {
                for (PollClient client : clients) {
                    rows.addAll(measureClient(client, threads, config, loadRunner));
                }
            }
        }
        return rows;
    }

    private static List<LatencyRow> measureClient(PollClient client, int threads, BenchmarkConfig config,
                                                  LoadRunner loadRunner) throws InterruptedException {
        System.out.printf("Measuring %s with %d thread(s)...%n", client.protocolName(), threads);
        Workload workload = Workload.prepare(client);
        List<LatencyRow> rows = new ArrayList<>();

        for (Operation operation : Operation.values()) {
            RequestAction action = workload.actionFor(operation);
            loadRunner.run(config.warmup(), threads, action);
            LatencyStats stats = loadRunner.run(config.requests(), threads, action);
            rows.add(new LatencyRow(client.protocolName(), operation, threads, stats));
        }
        return rows;
    }

    private static List<PayloadComparison> measurePayload() throws Exception {
        PollService pollService = new PollService(Clock.systemUTC());
        FaultInjector noFaults = FaultInjector.disabled();
        try (GrpcServer grpcServer = new GrpcServer(ANY_FREE_PORT, pollService, noFaults);
             RestServer restServer = new RestServer(ANY_FREE_PORT, pollService, noFaults)) {
            grpcServer.start();
            restServer.start();
            try (PayloadMeter payloadMeter = new PayloadMeter(HOST, grpcServer.port(), restServer.port())) {
                return payloadMeter.measure(Workload.samplePoll());
            }
        }
    }
}
