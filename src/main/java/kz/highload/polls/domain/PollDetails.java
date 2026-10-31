package kz.highload.polls.domain;

public record PollDetails(Poll poll, boolean open, int myOptionId) {
    public static final int NOT_VOTED = 0;
}
