from concurrent.futures import ThreadPoolExecutor

import pytest

from polls_service.errors import InvalidPollError, NotPollOwnerError
from polls_service.models import CreatePollCommand, VoteOutcome
from polls_service.service import PollService

NOW_MS = 1_800_000_000_000
DAY_MS = 24 * 60 * 60 * 1000
OWNER_ID = 7
USER_ID = 42


def poll_ending_at(ends_at_ms: int) -> CreatePollCommand:
    return CreatePollCommand(OWNER_ID, "Best language?", ("Java", "Python", "Go"), ends_at_ms)


def fixed_clock() -> int:
    return NOW_MS


@pytest.fixture
def poll_service() -> PollService:
    return PollService(clock=fixed_clock)


@pytest.fixture
def poll_id(poll_service: PollService) -> int:
    return poll_service.create_poll("key-1", poll_ending_at(NOW_MS + DAY_MS))


def test_first_vote_is_accepted(poll_service, poll_id):
    assert poll_service.cast_vote(poll_id, USER_ID, 1) is VoteOutcome.ACCEPTED


def test_repeated_same_vote_is_counted_once(poll_service, poll_id):
    poll_service.cast_vote(poll_id, USER_ID, 1)

    assert poll_service.cast_vote(poll_id, USER_ID, 1) is VoteOutcome.ALREADY_COUNTED
    assert poll_service.get_results(poll_id).total_votes == 1


def test_vote_for_another_option_is_conflict(poll_service, poll_id):
    poll_service.cast_vote(poll_id, USER_ID, 1)

    assert poll_service.cast_vote(poll_id, USER_ID, 2) is VoteOutcome.CONFLICT


def test_vote_after_end_is_rejected(poll_service):
    closed_poll_id = poll_service.create_poll("key-closed", poll_ending_at(NOW_MS - 1))

    assert poll_service.cast_vote(closed_poll_id, USER_ID, 1) is VoteOutcome.POLL_CLOSED


def test_unknown_option_is_rejected(poll_service, poll_id):
    assert poll_service.cast_vote(poll_id, USER_ID, 99) is VoteOutcome.INVALID_OPTION


def test_same_idempotency_key_returns_same_poll(poll_service, poll_id):
    retried_poll_id = poll_service.create_poll("key-1", poll_ending_at(NOW_MS + DAY_MS))
    other_poll_id = poll_service.create_poll("key-2", poll_ending_at(NOW_MS + DAY_MS))

    assert retried_poll_id == poll_id
    assert other_poll_id != poll_id


def test_poll_needs_two_to_ten_options(poll_service):
    one_option = CreatePollCommand(OWNER_ID, "Question?", ("Only",), NOW_MS + DAY_MS)

    with pytest.raises(InvalidPollError):
        poll_service.create_poll("key-invalid", one_option)


def test_concurrent_votes_of_one_user_are_counted_once(poll_service, poll_id):
    with ThreadPoolExecutor(max_workers=50) as executor:
        outcomes = list(executor.map(lambda _: poll_service.cast_vote(poll_id, USER_ID, 2), range(50)))

    assert outcomes.count(VoteOutcome.ACCEPTED) == 1
    assert poll_service.get_results(poll_id).total_votes == 1


def test_results_contain_counts_and_percents(poll_service, poll_id):
    for user_id, option_id in [(1, 1), (2, 1), (3, 1), (4, 2)]:
        poll_service.cast_vote(poll_id, user_id, option_id)

    results = poll_service.get_results(poll_id)

    assert results.total_votes == 4
    assert results.options[0].votes == 3
    assert results.options[0].percent == 75.0
    assert results.options[1].percent == 25.0


def test_get_poll_shows_my_vote(poll_service, poll_id):
    poll_service.cast_vote(poll_id, USER_ID, 2)

    assert poll_service.get_poll(poll_id, USER_ID).my_option_id == 2
    assert poll_service.get_poll(poll_id, 1).my_option_id == 0


def test_only_author_can_close_and_close_is_idempotent(poll_service, poll_id):
    with pytest.raises(NotPollOwnerError):
        poll_service.close_poll(poll_id, USER_ID)

    first_close = poll_service.close_poll(poll_id, OWNER_ID)
    second_close = poll_service.close_poll(poll_id, OWNER_ID)

    assert first_close.ends_at_ms == second_close.ends_at_ms == NOW_MS
    assert poll_service.cast_vote(poll_id, USER_ID, 1) is VoteOutcome.POLL_CLOSED
