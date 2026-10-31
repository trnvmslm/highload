package kz.highload.polls.domain;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.IntSupplier;

public class IdempotencyStore {
    private final ConcurrentMap<String, Integer> pollIdByKey = new ConcurrentHashMap<>();

    public int getOrCreate(String idempotencyKey, IntSupplier creator) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return creator.getAsInt();
        }
        return pollIdByKey.computeIfAbsent(idempotencyKey, key -> creator.getAsInt());
    }
}
