package kz.highload.polls.domain;

public class PollNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PollNotFoundException(int pollId) {
        super("Poll " + pollId + " not found");
    }
}
