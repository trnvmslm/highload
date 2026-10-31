package kz.highload.polls.domain;

import java.util.List;

public record PollResults(int pollId, long totalVotes, List<OptionCount> options) {
    public PollResults {
        options = List.copyOf(options);
    }
}
