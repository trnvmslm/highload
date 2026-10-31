import time

import pytest

from polls_service.fault import FaultInjector
from polls_service.grpc_client import GrpcPollClient
from polls_service.grpc_server import GrpcServer
from polls_service.models import CreatePollCommand, VoteOutcome
from polls_service.rest_client import RestPollClient
from polls_service.rest_server import RestServer
from polls_service.retry import ClientSettings, RetryPolicy
from polls_service.service import PollService

HOST = "127.0.0.1"
FAILURE_RATE = 0.1
VOTES = 100
SETTINGS = ClientSettings(
    timeout_s=5.0,
    retry_policy=RetryPolicy(max_attempts=5, initial_backoff_s=0.005, max_backoff_s=0.05),
)


@pytest.fixture
def servers():
    poll_service = PollService()
    fault_injector = FaultInjector(FAILURE_RATE)
    with GrpcServer(0, poll_service, fault_injector) as grpc_server, \
            RestServer(0, poll_service, fault_injector) as rest_server:
        yield grpc_server, rest_server


def assert_all_votes_stored_once(client) -> None:
    poll = CreatePollCommand(1, "Retry test?", ("Yes", "No"), int(time.time() * 1000) + 3_600_000)
    poll_id = client.create_poll("retry-test-poll", poll)

    for user_id in range(1, VOTES + 1):
        assert client.cast_vote(poll_id, user_id, 1) is VoteOutcome.ACCEPTED
    assert client.get_results(poll_id).total_votes == VOTES


def test_grpc_client_retries_unavailable(servers):
    grpc_server, _ = servers
    client = GrpcPollClient(HOST, grpc_server.port, SETTINGS)
    assert_all_votes_stored_once(client)
    client.close()


def test_rest_client_retries_service_unavailable(servers):
    _, rest_server = servers
    client = RestPollClient(HOST, rest_server.port, SETTINGS)
    assert_all_votes_stored_once(client)
    client.close()
