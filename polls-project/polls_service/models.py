from dataclasses import dataclass, replace
from enum import Enum

NOT_VOTED = 0


class VoteOutcome(Enum):
    ACCEPTED = "ACCEPTED"
    ALREADY_COUNTED = "ALREADY_COUNTED"
    CONFLICT = "CONFLICT"
    POLL_CLOSED = "POLL_CLOSED"
    INVALID_OPTION = "INVALID_OPTION"


@dataclass(frozen=True)
class CreatePollCommand:
    owner_id: int
    question: str
    options: tuple[str, ...]
    ends_at_ms: int


@dataclass(frozen=True)
class Poll:
    id: int
    owner_id: int
    question: str
    options: tuple[str, ...]
    ends_at_ms: int

    def is_open_at(self, moment_ms: int) -> bool:
        return moment_ms < self.ends_at_ms

    def has_option(self, option_id: int) -> bool:
        return 1 <= option_id <= len(self.options)

    def closed_at(self, moment_ms: int) -> "Poll":
        return replace(self, ends_at_ms=moment_ms)


@dataclass(frozen=True)
class OptionCount:
    option_id: int
    text: str
    votes: int
    percent: float


@dataclass(frozen=True)
class PollResults:
    poll_id: int
    total_votes: int
    options: tuple[OptionCount, ...]


@dataclass(frozen=True)
class PollDetails:
    poll: Poll
    is_open: bool
    my_option_id: int
