import itertools
import threading
import time
import uuid
from enum import Enum
from typing import Callable

from polls_service.models import CreatePollCommand, VoteOutcome

OWNER_ID = 42
FIRST_USER_ID = 1_000_000
POLL_DURATION_MS = 7 * 24 * 60 * 60 * 1000


class Operation(Enum):
    CREATE_POLL = "CreatePoll"
    CAST_VOTE = "CastVote"
    GET_RESULTS = "GetResults"


def sample_poll() -> CreatePollCommand:
    ends_at_ms = int(time.time() * 1000) + POLL_DURATION_MS
    return CreatePollCommand(
        OWNER_ID,
        "Which programming language should our team learn next?",
        ("Java", "Python", "Go", "Kotlin"),
        ends_at_ms,
    )


class Workload:
    def __init__(self, client):
        self._client = client
        poll = sample_poll()
        self._poll_id = client.create_poll(str(uuid.uuid4()), poll)
        self._option_count = len(poll.options)
        self._user_ids = itertools.count(FIRST_USER_ID)
        self._user_ids_lock = threading.Lock()

    def action_for(self, operation: Operation) -> Callable[[int], None]:
        actions = {
            Operation.CREATE_POLL: self._create_poll,
            Operation.CAST_VOTE: self._cast_vote,
            Operation.GET_RESULTS: self._get_results,
        }
        return actions[operation]

    def _create_poll(self, request_index: int) -> None:
        self._client.create_poll(str(uuid.uuid4()), sample_poll())

    def _cast_vote(self, request_index: int) -> None:
        with self._user_ids_lock:
            user_id = next(self._user_ids)
        option_id = 1 + request_index % self._option_count
        outcome = self._client.cast_vote(self._poll_id, user_id, option_id)
        if outcome is not VoteOutcome.ACCEPTED:
            raise RuntimeError(f"Unexpected vote outcome {outcome}")

    def _get_results(self, request_index: int) -> None:
        self._client.get_results(self._poll_id)
