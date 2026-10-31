package kz.highload.polls.domain;

import java.time.Instant;
import java.util.List;

public record Poll(int id, int ownerId, String question, List<String> options, Instant endsAt) {
    public Poll {
        options = List.copyOf(options);
    }

    public boolean isOpenAt(Instant moment) {
        return moment.isBefore(endsAt);
    }

    public boolean hasOption(int optionId) {
        return optionId >= 1 && optionId <= options.size();
    }

    public Poll closedAt(Instant moment) {
        return new Poll(id, ownerId, question, options, moment);
    }
}
