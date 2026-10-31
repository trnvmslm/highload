import time
from typing import Callable

from polls_service.errors import InvalidPollError, NotPollOwnerError, PollNotFoundError
from polls_service.models import (
    NOT_VOTED,
    CreatePollCommand,
    OptionCount,
    Poll,
    PollDetails,
    PollResults,
    VoteOutcome,
)
from polls_service.repositories import IdempotencyStore, PollRepository, VoteRepository

MIN_OPTIONS = 2
MAX_OPTIONS = 10


def current_time_ms() -> int:
    return int(time.time() * 1000)


class PollService:
    def __init__(self, clock: Callable[[], int] = current_time_ms):
        self._polls = PollRepository()
        self._votes = VoteRepository()
        self._idempotency = IdempotencyStore()
        self._clock = clock

    def create_poll(self, idempotency_key: str, command: CreatePollCommand) -> int:
        _validate(command)
        return self._idempotency.get_or_create(idempotency_key, lambda: self._store_poll(command))

    def cast_vote(self, poll_id: int, user_id: int, option_id: int) -> VoteOutcome:
        poll = self._find_poll(poll_id)
        if not poll.has_option(option_id):
            return VoteOutcome.INVALID_OPTION
        if not poll.is_open_at(self._clock()):
            return VoteOutcome.POLL_CLOSED
        previous_option = self._votes.insert_if_absent(poll_id, user_id, option_id)
        if previous_option is None:
            return VoteOutcome.ACCEPTED
        if previous_option == option_id:
            return VoteOutcome.ALREADY_COUNTED
        return VoteOutcome.CONFLICT

    def get_results(self, poll_id: int) -> PollResults:
        poll = self._find_poll(poll_id)
        counts = self._votes.counts_for(poll_id)
        total_votes = sum(counts)
        options = tuple(
            OptionCount(index + 1, text, votes, _percent_of(votes, total_votes))
            for index, (text, votes) in enumerate(zip(poll.options, counts))
        )
        return PollResults(poll_id, total_votes, options)

    def get_poll(self, poll_id: int, user_id: int) -> PollDetails:
        poll = self._find_poll(poll_id)
        my_option_id = self._votes.find_vote(poll_id, user_id) or NOT_VOTED
        return PollDetails(poll, poll.is_open_at(self._clock()), my_option_id)

    def close_poll(self, poll_id: int, user_id: int) -> Poll:
        poll = self._find_poll(poll_id)
        if poll.owner_id != user_id:
            raise NotPollOwnerError(poll_id, user_id)
        return self._polls.close_if_open(poll_id, self._clock())

    def _store_poll(self, command: CreatePollCommand) -> int:
        poll = self._polls.create(command)
        self._votes.register_poll(poll.id, len(poll.options))
        return poll.id

    def _find_poll(self, poll_id: int) -> Poll:
        poll = self._polls.find_by_id(poll_id)
        if poll is None:
            raise PollNotFoundError(poll_id)
        return poll


def _validate(command: CreatePollCommand) -> None:
    if not command.question or not command.question.strip():
        raise InvalidPollError("Question must not be empty")
    if not MIN_OPTIONS <= len(command.options) <= MAX_OPTIONS:
        raise InvalidPollError(f"A poll needs {MIN_OPTIONS}-{MAX_OPTIONS} options")


def _percent_of(votes: int, total_votes: int) -> float:
    if total_votes == 0:
        return 0.0
    return round(votes * 100.0 / total_votes, 1)
