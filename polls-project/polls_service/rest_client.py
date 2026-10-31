import threading

import requests

from polls_service.errors import PollClientError, RetryableStatusError
from polls_service.models import CreatePollCommand, OptionCount, PollResults, VoteOutcome
from polls_service.rest_server import IDEMPOTENCY_KEY_HEADER, to_json_bytes
from polls_service.retry import ClientSettings, RetryExecutor

JSON_HEADERS = {"Content-Type": "application/json", "Accept": "application/json"}
RETRYABLE_ERRORS = (requests.ConnectionError, requests.Timeout, RetryableStatusError)

VOTE_OUTCOMES = {
    201: VoteOutcome.ACCEPTED,
    200: VoteOutcome.ALREADY_COUNTED,
    409: VoteOutcome.CONFLICT,
    410: VoteOutcome.POLL_CLOSED,
    400: VoteOutcome.INVALID_OPTION,
}


class RestPollClient:
    protocol_name = "REST/JSON"

    def __init__(self, host: str, port: int, settings: ClientSettings = ClientSettings()):
        self._polls_url = f"http://{host}:{port}/v1/polls"
        self._timeout_s = settings.timeout_s
        self._retry_executor = RetryExecutor(settings.retry_policy, RETRYABLE_ERRORS)
        self._local = threading.local()
        self._sessions: list[requests.Session] = []
        self._sessions_lock = threading.Lock()

    def create_poll(self, idempotency_key: str, command: CreatePollCommand) -> int:
        body = {
            "ownerId": command.owner_id,
            "question": command.question,
            "options": list(command.options),
            "endsAtEpochMs": command.ends_at_ms,
        }
        headers = {**JSON_HEADERS, IDEMPOTENCY_KEY_HEADER: idempotency_key}
        response = self._send("POST", self._polls_url, body, headers)
        if response.status_code != 201:
            raise PollClientError(f"CreatePoll failed with HTTP {response.status_code}")
        return response.json()["pollId"]

    def cast_vote(self, poll_id: int, user_id: int, option_id: int) -> VoteOutcome:
        url = f"{self._polls_url}/{poll_id}/votes"
        response = self._send("POST", url, {"userId": user_id, "optionId": option_id}, JSON_HEADERS)
        if response.status_code not in VOTE_OUTCOMES:
            raise PollClientError(f"CastVote failed with HTTP {response.status_code}")
        return VOTE_OUTCOMES[response.status_code]

    def get_results(self, poll_id: int) -> PollResults:
        url = f"{self._polls_url}/{poll_id}/results"
        response = self._send("GET", url, None, JSON_HEADERS)
        if response.status_code != 200:
            raise PollClientError(f"GetResults failed with HTTP {response.status_code}")
        body = response.json()
        options = tuple(
            OptionCount(option["optionId"], option["text"], option["votes"], option["percent"])
            for option in body["options"]
        )
        return PollResults(body["pollId"], body["totalVotes"], options)

    def close(self) -> None:
        with self._sessions_lock:
            for session in self._sessions:
                session.close()

    def _send(self, method: str, url: str, body: dict | None, headers: dict) -> requests.Response:
        data = to_json_bytes(body) if body is not None else None
        return self._retry_executor.execute(lambda: self._send_once(method, url, data, headers))

    def _send_once(self, method: str, url: str, data: bytes | None, headers: dict) -> requests.Response:
        response = self._session().request(method, url, data=data, headers=headers, timeout=self._timeout_s)
        if response.status_code == 503:
            raise RetryableStatusError(response.status_code)
        return response

    def _session(self) -> requests.Session:
        if not hasattr(self._local, "session"):
            self._local.session = requests.Session()
            with self._sessions_lock:
                self._sessions.append(self._local.session)
        return self._local.session
