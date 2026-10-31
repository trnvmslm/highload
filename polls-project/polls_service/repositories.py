import threading
from typing import Callable

from polls_service.errors import PollNotFoundError
from polls_service.models import CreatePollCommand, Poll


class PollRepository:
    def __init__(self):
        self._lock = threading.Lock()
        self._last_id = 0
        self._polls_by_id: dict[int, Poll] = {}

    def create(self, command: CreatePollCommand) -> Poll:
        with self._lock:
            self._last_id += 1
            poll = Poll(self._last_id, command.owner_id, command.question, command.options, command.ends_at_ms)
            self._polls_by_id[poll.id] = poll
            return poll

    def find_by_id(self, poll_id: int) -> Poll | None:
        return self._polls_by_id.get(poll_id)

    def close_if_open(self, poll_id: int, now_ms: int) -> Poll:
        with self._lock:
            poll = self._polls_by_id.get(poll_id)
            if poll is None:
                raise PollNotFoundError(poll_id)
            if poll.is_open_at(now_ms):
                poll = poll.closed_at(now_ms)
                self._polls_by_id[poll_id] = poll
            return poll


class VoteRepository:
    def __init__(self):
        self._lock = threading.Lock()
        self._option_by_voter: dict[tuple[int, int], int] = {}
        self._counters_by_poll: dict[int, list[int]] = {}

    def register_poll(self, poll_id: int, option_count: int) -> None:
        with self._lock:
            self._counters_by_poll[poll_id] = [0] * option_count

    def insert_if_absent(self, poll_id: int, user_id: int, option_id: int) -> int | None:
        with self._lock:
            existing_option = self._option_by_voter.get((poll_id, user_id))
            if existing_option is None:
                self._option_by_voter[(poll_id, user_id)] = option_id
                self._counters_by_poll[poll_id][option_id - 1] += 1
            return existing_option

    def find_vote(self, poll_id: int, user_id: int) -> int | None:
        return self._option_by_voter.get((poll_id, user_id))

    def counts_for(self, poll_id: int) -> list[int]:
        with self._lock:
            return list(self._counters_by_poll[poll_id])


class IdempotencyStore:
    def __init__(self):
        self._lock = threading.Lock()
        self._poll_id_by_key: dict[str, int] = {}

    def get_or_create(self, idempotency_key: str, creator: Callable[[], int]) -> int:
        if not idempotency_key:
            return creator()
        with self._lock:
            if idempotency_key not in self._poll_id_by_key:
                self._poll_id_by_key[idempotency_key] = creator()
            return self._poll_id_by_key[idempotency_key]
