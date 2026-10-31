import random


class FaultInjector:
    def __init__(self, failure_rate: float = 0.0):
        if not 0.0 <= failure_rate < 1.0:
            raise ValueError("failure_rate must be in [0, 1)")
        self._failure_rate = failure_rate

    def should_fail(self) -> bool:
        return self._failure_rate > 0.0 and random.random() < self._failure_rate
