package kz.highload.polls.domain;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLongArray;

public class VoteRepository {
    private record VoteKey(int pollId, int userId) {
    }

    private final ConcurrentMap<VoteKey, Integer> optionByVoter = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, AtomicLongArray> countersByPoll = new ConcurrentHashMap<>();

    public void registerPoll(int pollId, int optionCount) {
        countersByPoll.put(pollId, new AtomicLongArray(optionCount));
    }

    public Optional<Integer> insertIfAbsent(int pollId, int userId, int optionId) {
        Integer existingOption = optionByVoter.putIfAbsent(new VoteKey(pollId, userId), optionId);
        if (existingOption == null) {
            countersByPoll.get(pollId).incrementAndGet(optionId - 1);
        }
        return Optional.ofNullable(existingOption);
    }

    public Optional<Integer> findVote(int pollId, int userId) {
        return Optional.ofNullable(optionByVoter.get(new VoteKey(pollId, userId)));
    }

    public long[] countsFor(int pollId) {
        AtomicLongArray counters = countersByPoll.get(pollId);
        long[] counts = new long[counters.length()];
        for (int index = 0; index < counts.length; index++) {
            counts[index] = counters.get(index);
        }
        return counts;
    }
}
