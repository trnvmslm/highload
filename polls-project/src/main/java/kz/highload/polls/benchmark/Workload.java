package kz.highload.polls.benchmark;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import kz.highload.polls.client.PollClient;
import kz.highload.polls.domain.CreatePollCommand;
import kz.highload.polls.domain.VoteOutcome;

public final class Workload {
    private static final int OWNER_ID = 42;
    private static final int FIRST_USER_ID = 1_000_000;
    private static final Duration POLL_DURATION = Duration.ofDays(7);

    private final PollClient client;
    private final int pollId;
    private final int optionCount;
    private final AtomicInteger userSequence = new AtomicInteger(FIRST_USER_ID);

    private Workload(PollClient client, int pollId, int optionCount) {
        this.client = client;
        this.pollId = pollId;
        this.optionCount = optionCount;
    }

    public static Workload prepare(PollClient client) {
        CreatePollCommand poll = samplePoll();
        int pollId = client.createPoll(UUID.randomUUID().toString(), poll);
        return new Workload(client, pollId, poll.options().size());
    }

    public static CreatePollCommand samplePoll() {
        return new CreatePollCommand(
                OWNER_ID,
                "Which programming language should our team learn next?",
                List.of("Java", "Python", "Go", "Kotlin"),
                Instant.now().plus(POLL_DURATION));
    }

    public RequestAction actionFor(Operation operation) {
        return switch (operation) {
            case CREATE_POLL -> this::createPoll;
            case CAST_VOTE -> this::castVote;
            case GET_RESULTS -> this::getResults;
        };
    }

    private void createPoll(int requestIndex) {
        client.createPoll(UUID.randomUUID().toString(), samplePoll());
    }

    private void castVote(int requestIndex) {
        int userId = userSequence.incrementAndGet();
        int optionId = 1 + requestIndex % optionCount;
        VoteOutcome outcome = client.castVote(pollId, userId, optionId);
        if (outcome != VoteOutcome.ACCEPTED) {
            throw new IllegalStateException("Unexpected vote outcome " + outcome);
        }
    }

    private void getResults(int requestIndex) {
        client.getResults(pollId);
    }
}
