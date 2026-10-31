package kz.highload.polls.domain;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class PollService {
    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 10;

    private final PollRepository pollRepository;
    private final VoteRepository voteRepository;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    public PollService(Clock clock) {
        this.pollRepository = new PollRepository();
        this.voteRepository = new VoteRepository();
        this.idempotencyStore = new IdempotencyStore();
        this.clock = clock;
    }

    public int createPoll(String idempotencyKey, CreatePollCommand command) {
        validate(command);
        return idempotencyStore.getOrCreate(idempotencyKey, () -> storePoll(command));
    }

    public VoteOutcome castVote(int pollId, int userId, int optionId) {
        Poll poll = findPoll(pollId);
        if (!poll.hasOption(optionId)) {
            return VoteOutcome.INVALID_OPTION;
        }
        if (!poll.isOpenAt(clock.instant())) {
            return VoteOutcome.POLL_CLOSED;
        }
        Optional<Integer> previousOption = voteRepository.insertIfAbsent(pollId, userId, optionId);
        if (previousOption.isEmpty()) {
            return VoteOutcome.ACCEPTED;
        }
        if (previousOption.get() == optionId) {
            return VoteOutcome.ALREADY_COUNTED;
        }
        return VoteOutcome.CONFLICT;
    }

    public PollResults getResults(int pollId) {
        Poll poll = findPoll(pollId);
        long[] counts = voteRepository.countsFor(pollId);
        long totalVotes = Arrays.stream(counts).sum();

        List<OptionCount> rows = new ArrayList<>(counts.length);
        for (int index = 0; index < counts.length; index++) {
            int optionId = index + 1;
            String text = poll.options().get(index);
            rows.add(new OptionCount(optionId, text, counts[index], percentOf(counts[index], totalVotes)));
        }
        return new PollResults(pollId, totalVotes, rows);
    }

    public PollDetails getPoll(int pollId, int userId) {
        Poll poll = findPoll(pollId);
        boolean open = poll.isOpenAt(clock.instant());
        int myOptionId = voteRepository.findVote(pollId, userId).orElse(PollDetails.NOT_VOTED);
        return new PollDetails(poll, open, myOptionId);
    }

    public Poll closePoll(int pollId, int userId) {
        Poll poll = findPoll(pollId);
        if (poll.ownerId() != userId) {
            throw new NotPollOwnerException(pollId, userId);
        }
        return pollRepository.closeIfOpen(pollId, clock.instant());
    }

    private int storePoll(CreatePollCommand command) {
        Poll poll = pollRepository.create(command);
        voteRepository.registerPoll(poll.id(), poll.options().size());
        return poll.id();
    }

    private Poll findPoll(int pollId) {
        return pollRepository.findById(pollId).orElseThrow(() -> new PollNotFoundException(pollId));
    }

    private static void validate(CreatePollCommand command) {
        if (command.question() == null || command.question().isBlank()) {
            throw new InvalidPollException("Question must not be empty");
        }
        int optionCount = command.options().size();
        if (optionCount < MIN_OPTIONS || optionCount > MAX_OPTIONS) {
            throw new InvalidPollException("A poll needs " + MIN_OPTIONS + "-" + MAX_OPTIONS + " options");
        }
    }

    private static double percentOf(long votes, long totalVotes) {
        if (totalVotes == 0) {
            return 0.0;
        }
        double percent = votes * 100.0 / totalVotes;
        return Math.round(percent * 10.0) / 10.0;
    }
}
