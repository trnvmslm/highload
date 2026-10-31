package kz.highload.polls.domain;

public enum VoteOutcome {
    ACCEPTED,
    ALREADY_COUNTED,
    CONFLICT,
    POLL_CLOSED,
    INVALID_OPTION
}
