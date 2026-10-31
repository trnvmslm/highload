package kz.highload.polls.client;

public class PollClientException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PollClientException(String message) {
        super(message);
    }

    public PollClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
