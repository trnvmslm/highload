package kz.highload.polls.rest.dto;

import java.util.List;

import kz.highload.polls.domain.Poll;
import kz.highload.polls.domain.PollDetails;

public record PollReply(int pollId, int ownerId, String question, List<String> options,
                        long endsAtEpochMs, boolean open, int myOptionId) {
    public static PollReply from(PollDetails details) {
        Poll poll = details.poll();
        return new PollReply(poll.id(), poll.ownerId(), poll.question(), poll.options(),
                poll.endsAt().toEpochMilli(), details.open(), details.myOptionId());
    }
}
