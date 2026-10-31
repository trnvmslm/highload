import random
import time
from dataclasses import dataclass, field
from typing import Callable, TypeVar

from polls_service.errors import PollClientError

T = TypeVar("T")


@dataclass(frozen=True)
class RetryPolicy:
    max_attempts: int = 4
    initial_backoff_s: float = 0.1
    max_backoff_s: float = 1.0
    backoff_multiplier: float = 2.0

    def backoff_before_retry(self, retry_number: int) -> float:
        exponential = self.initial_backoff_s * self.backoff_multiplier ** (retry_number - 1)
        return random.uniform(0, min(exponential, self.max_backoff_s))


@dataclass(frozen=True)
class ClientSettings:
    timeout_s: float = 2.0
    retry_policy: RetryPolicy = field(default_factory=RetryPolicy)


class RetryExecutor:
    def __init__(self, policy: RetryPolicy, retryable_errors: tuple[type[Exception], ...]):
        self._policy = policy
        self._retryable_errors = retryable_errors

    def execute(self, call: Callable[[], T]) -> T:
        last_error = None
        for attempt in range(1, self._policy.max_attempts + 1):
            if attempt > 1:
                time.sleep(self._policy.backoff_before_retry(attempt - 1))
            try:
                return call()
            except self._retryable_errors as error:
                last_error = error
        raise PollClientError(f"Gave up after {self._policy.max_attempts} attempts") from last_error
