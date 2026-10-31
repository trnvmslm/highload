package kz.highload.polls.benchmark;

@FunctionalInterface
public interface RequestAction {
    void run(int requestIndex);
}
