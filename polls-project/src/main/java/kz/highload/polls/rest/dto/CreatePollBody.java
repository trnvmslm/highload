package kz.highload.polls.rest.dto;

import java.util.List;

public record CreatePollBody(int ownerId, String question, List<String> options, long endsAtEpochMs) {
}
