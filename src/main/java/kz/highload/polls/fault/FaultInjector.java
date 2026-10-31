package kz.highload.polls.fault;

import java.util.concurrent.ThreadLocalRandom;

public class FaultInjector {
    private final double failureRate;

    public FaultInjector(double failureRate) {
        if (failureRate < 0.0 || failureRate >= 1.0) {
            throw new IllegalArgumentException("failureRate must be in [0, 1)");
        }
        this.failureRate = failureRate;
    }

    public static FaultInjector disabled() {
        return new FaultInjector(0.0);
    }

    public boolean shouldFail() {
        return failureRate > 0.0 && ThreadLocalRandom.current().nextDouble() < failureRate;
    }
}
