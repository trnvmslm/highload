package kz.highload.polls.domain;

public class NotPollOwnerException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NotPollOwnerException(int pollId, int userId) {
        super("User " + userId + " is not the author of poll " + pollId);
    }
}
