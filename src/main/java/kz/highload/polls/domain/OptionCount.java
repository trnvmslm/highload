package kz.highload.polls.domain;

public record OptionCount(int optionId, String text, long votes, double percent) {
}
