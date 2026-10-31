package kz.highload.polls.client;

import java.io.IOException;

@FunctionalInterface
public interface RetryableCall<T> {
    T attempt() throws IOException;
}
