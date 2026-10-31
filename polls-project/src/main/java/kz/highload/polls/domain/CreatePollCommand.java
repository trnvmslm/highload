package kz.highload.polls.domain;

import java.time.Instant;
import java.util.List;

public record CreatePollCommand(int ownerId, String question, List<String> options, Instant endsAt) {
    public CreatePollCommand {
        options = List.copyOf(options);
    }
}
