package kz.highload.polls.client;

import java.io.IOException;

public class RetryableStatusException extends IOException {
    private static final long serialVersionUID = 1L;

    public RetryableStatusException(int statusCode) {
        super("Server answered HTTP " + statusCode);
    }
}
