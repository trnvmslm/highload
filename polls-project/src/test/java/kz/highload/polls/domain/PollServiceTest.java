package kz.highload.polls.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PollServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final int USER_ID = 42;

    private PollService pollService;
    private int pollId;

    @BeforeEach
    void setUp() {
        pollService = new PollService(Clock.fixed(NOW, ZoneOffset.UTC));
        pollId = pollService.createPoll("key-1", pollEndingAt(NOW.plus(Duration.ofDays(1))));
    }

    @Test
    void firstVoteIsAccepted() {
        assertEquals(VoteOutcome.ACCEPTED, pollService.castVote(pollId, USER_ID, 1));
    }

    @Test
    void repeatedSameVoteIsAlreadyCountedAndNotDoubled() {
        pollService.castVote(pollId, USER_ID, 1);

        assertEquals(VoteOutcome.ALREADY_COUNTED, pollService.castVote(pollId, USER_ID, 1));
        assertEquals(1, pollService.getResults(pollId).totalVotes());
    }

    @Test
    void voteForAnotherOptionIsConflict() {
        pollService.castVote(pollId, USER_ID, 1);

        assertEquals(VoteOutcome.CONFLICT, pollService.castVote(pollId, USER_ID, 2));
    }

    @Test
    void voteAfterEndIsRejected() {
        int closedPollId = pollService.createPoll("key-closed", pollEndingAt(NOW.minusSeconds(1)));

        assertEquals(VoteOutcome.POLL_CLOSED, pollService.castVote(closedPollId, USER_ID, 1));
    }

    @Test
    void unknownOptionIsRejected() {
        assertEquals(VoteOutcome.INVALID_OPTION, pollService.castVote(pollId, USER_ID, 99));
    }

    @Test
    void sameIdempotencyKeyReturnsSamePoll() {
        int retriedPollId = pollService.createPoll("key-1", pollEndingAt(NOW.plus(Duration.ofDays(1))));
        int otherPollId = pollService.createPoll("key-2", pollEndingAt(NOW.plus(Duration.ofDays(1))));

        assertEquals(pollId, retriedPollId);
        assertNotEquals(pollId, otherPollId);
    }

    @Test
    void pollNeedsTwoToTenOptions() {
        CreatePollCommand oneOption = new CreatePollCommand(1, "Question?", List.of("Only"), NOW.plusSeconds(60));

        assertThrows(InvalidPollException.class, () -> pollService.createPoll("key-invalid", oneOption));
    }

    @Test
    void concurrentVotesOfOneUserAreCountedOnce() throws Exception {
        int parallelRequests = 50;
        ExecutorService executor = Executors.newFixedThreadPool(parallelRequests);
        List<Callable<VoteOutcome>> sameVote = new ArrayList<>();
        for (int request = 0; request < parallelRequests; request++) {
            sameVote.add(() -> pollService.castVote(pollId, USER_ID, 2));
        }

        List<Future<VoteOutcome>> outcomes = executor.invokeAll(sameVote);
        executor.shutdown();

        long accepted = 0;
        for (Future<VoteOutcome> outcome : outcomes) {
            if (outcome.get() == VoteOutcome.ACCEPTED) {
                accepted++;
            }
        }
        assertEquals(1, accepted);
        assertEquals(1, pollService.getResults(pollId).totalVotes());
    }

    @Test
    void resultsContainCountsAndPercents() {
        pollService.castVote(pollId, 1, 1);
        pollService.castVote(pollId, 2, 1);
        pollService.castVote(pollId, 3, 1);
        pollService.castVote(pollId, 4, 2);

        PollResults results = pollService.getResults(pollId);

        assertEquals(4, results.totalVotes());
        assertEquals(3, results.options().get(0).votes());
        assertEquals(75.0, results.options().get(0).percent());
        assertEquals(25.0, results.options().get(1).percent());
    }

    private static CreatePollCommand pollEndingAt(Instant endsAt) {
        return new CreatePollCommand(7, "Best language?", List.of("Java", "Python", "Go"), endsAt);
    }
}
