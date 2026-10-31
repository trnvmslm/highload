package kz.highload.polls.domain;

public class InvalidPollException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidPollException(String message) {
        super(message);
    }
}
