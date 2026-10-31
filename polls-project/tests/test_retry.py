import pytest

from polls_service.errors import PollClientError, RetryableStatusError
from polls_service.retry import RetryExecutor, RetryPolicy

FAST_POLICY = RetryPolicy(max_attempts=4, initial_backoff_s=0.001, max_backoff_s=0.005)


class FlakyCall:
    def __init__(self, failures: int):
        self.calls = 0
        self._failures = failures

    def __call__(self) -> str:
        self.calls += 1
        if self.calls <= self._failures:
            raise RetryableStatusError(503)
        return "ok"


def test_retries_until_success():
    call = FlakyCall(failures=2)

    result = RetryExecutor(FAST_POLICY, (RetryableStatusError,)).execute(call)

    assert result == "ok"
    assert call.calls == 3


def test_gives_up_after_max_attempts():
    call = FlakyCall(failures=10)

    with pytest.raises(PollClientError):
        RetryExecutor(FAST_POLICY, (RetryableStatusError,)).execute(call)
    assert call.calls == 4


def test_backoff_never_exceeds_maximum():
    policy = RetryPolicy()

    assert all(policy.backoff_before_retry(retry) <= policy.max_backoff_s for retry in range(1, 11))
