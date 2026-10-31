package kz.highload.polls.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import kz.highload.polls.domain.CreatePollCommand;
import kz.highload.polls.domain.PollService;
import kz.highload.polls.domain.VoteOutcome;
import kz.highload.polls.fault.FaultInjector;
import kz.highload.polls.grpc.GrpcServer;
import kz.highload.polls.rest.RestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientRetryIntegrationTest {
    private static final double FAILURE_RATE = 0.1;
    private static final int VOTES = 100;
    private static final ClientSettings SETTINGS = new ClientSettings(
            Duration.ofSeconds(5),
            new RetryPolicy(5, Duration.ofMillis(5), Duration.ofMillis(50), 2.0));

    private GrpcServer grpcServer;
    private RestServer restServer;

    @BeforeEach
    void startServers() throws Exception {
        PollService pollService = new PollService(Clock.systemUTC());
        FaultInjector faultInjector = new FaultInjector(FAILURE_RATE);
        grpcServer = new GrpcServer(0, pollService, faultInjector);
        restServer = new RestServer(0, pollService, faultInjector);
        grpcServer.start();
        restServer.start();
    }

    @AfterEach
    void stopServers() throws Exception {
        grpcServer.close();
        restServer.close();
    }

    @Test
    void grpcClientRetriesUnavailable() {
        try (PollClient client = new GrpcPollClient("localhost", grpcServer.port(), SETTINGS)) {
            assertAllVotesStoredOnce(client);
        }
    }

    @Test
    void restClientRetriesServiceUnavailable() {
        try (PollClient client = new RestPollClient("localhost", restServer.port(), SETTINGS)) {
            assertAllVotesStoredOnce(client);
        }
    }

    private static void assertAllVotesStoredOnce(PollClient client) {
        CreatePollCommand poll = new CreatePollCommand(
                1, "Retry test?", List.of("Yes", "No"), Instant.now().plusSeconds(3600));
        int pollId = client.createPoll("retry-test-poll", poll);

        for (int userId = 1; userId <= VOTES; userId++) {
            assertEquals(VoteOutcome.ACCEPTED, client.castVote(pollId, userId, 1));
        }
        assertEquals(VOTES, client.getResults(pollId).totalVotes());
    }
}
