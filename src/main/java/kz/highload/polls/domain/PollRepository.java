package kz.highload.polls.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class PollRepository {
    private final AtomicInteger idSequence = new AtomicInteger();
    private final ConcurrentMap<Integer, Poll> pollsById = new ConcurrentHashMap<>();

    public Poll create(CreatePollCommand command) {
        int pollId = idSequence.incrementAndGet();
        Poll poll = new Poll(pollId, command.ownerId(), command.question(), command.options(), command.endsAt());
        pollsById.put(pollId, poll);
        return poll;
    }

    public Optional<Poll> findById(int pollId) {
        return Optional.ofNullable(pollsById.get(pollId));
    }

    public Poll closeIfOpen(int pollId, Instant now) {
        Poll updated = pollsById.computeIfPresent(pollId, (id, poll) -> poll.isOpenAt(now) ? poll.closedAt(now) : poll);
        if (updated == null) {
            throw new PollNotFoundException(pollId);
        }
        return updated;
    }
}
