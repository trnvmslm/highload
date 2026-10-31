package kz.highload.polls.client;

import kz.highload.polls.domain.CreatePollCommand;
import kz.highload.polls.domain.PollResults;
import kz.highload.polls.domain.VoteOutcome;

public interface PollClient extends AutoCloseable {
    int createPoll(String idempotencyKey, CreatePollCommand command);

    VoteOutcome castVote(int pollId, int userId, int optionId);

    PollResults getResults(int pollId);

    String protocolName();

    @Override
    void close();
}
