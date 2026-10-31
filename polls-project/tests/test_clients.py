import threading
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
VOTES = 100
SETTINGS = ClientSettings(
    timeout_s=5.0,
    retry_policy=RetryPolicy(max_attempts=4, initial_backoff_s=0.005, max_backoff_s=0.05),
)


class EveryThirdRequestFails(FaultInjector):
    def __init__(self):
        super().__init__()
        self.requests = 0
        self._lock = threading.Lock()

    def should_fail(self) -> bool:
        with self._lock:
            self.requests += 1
            return self.requests % 3 == 0


@pytest.fixture
def fault_injector() -> EveryThirdRequestFails:
    return EveryThirdRequestFails()


@pytest.fixture
def servers(fault_injector):
    poll_service = PollService()
    with GrpcServer(0, poll_service, fault_injector) as grpc_server, \
            RestServer(0, poll_service, fault_injector) as rest_server:
        yield grpc_server, rest_server


def assert_all_votes_stored_once(client, fault_injector: EveryThirdRequestFails) -> None:
    poll = CreatePollCommand(1, "Retry test?", ("Yes", "No"), int(time.time() * 1000) + 3_600_000)
    poll_id = client.create_poll("retry-test-poll", poll)

    for user_id in range(1, VOTES + 1):
        assert client.cast_vote(poll_id, user_id, 1) is VoteOutcome.ACCEPTED
    assert client.get_results(poll_id).total_votes == VOTES
    assert fault_injector.requests > VOTES


def test_grpc_client_retries_unavailable(servers, fault_injector):
    grpc_server, _ = servers
    client = GrpcPollClient(HOST, grpc_server.port, SETTINGS)
    assert_all_votes_stored_once(client, fault_injector)
    client.close()


def test_rest_client_retries_service_unavailable(servers, fault_injector):
    _, rest_server = servers
    client = RestPollClient(HOST, rest_server.port, SETTINGS)
    assert_all_votes_stored_once(client, fault_injector)
    client.close()