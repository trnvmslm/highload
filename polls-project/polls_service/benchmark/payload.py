from dataclasses import dataclass

import grpc
import requests

from polls_service import proto_mapper
from polls_service.api import polls_pb2, polls_pb2_grpc
from polls_service.benchmark.workload import Operation
from polls_service.models import CreatePollCommand
from polls_service.rest_server import IDEMPOTENCY_KEY_HEADER, to_json_bytes

SAMPLE_VOTERS = 1_000
FIRST_VOTER_ID = 5_000_000


@dataclass(frozen=True)
class PayloadComparison:
    operation: Operation
    grpc_request_bytes: int
    rest_request_bytes: int
    grpc_response_bytes: int
    rest_response_bytes: int

    @property
    def response_saving_percent(self) -> float:
        if self.rest_response_bytes == 0:
            return 0.0
        return 100 * (self.rest_response_bytes - self.grpc_response_bytes) / self.rest_response_bytes


class PayloadMeter:
    def __init__(self, host: str, grpc_port: int, rest_port: int):
        self._channel = grpc.insecure_channel(f"{host}:{grpc_port}")
        self._stub = polls_pb2_grpc.PollServiceStub(self._channel)
        self._session = requests.Session()
        self._polls_url = f"http://{host}:{rest_port}/v1/polls"

    def measure(self, sample_poll: CreatePollCommand) -> list[PayloadComparison]:
        grpc_request = proto_mapper.to_create_request("payload-grpc", sample_poll)
        grpc_response = self._stub.CreatePoll(grpc_request)
        rest_request = to_json_bytes({
            "ownerId": sample_poll.owner_id,
            "question": sample_poll.question,
            "options": list(sample_poll.options),
            "endsAtEpochMs": sample_poll.ends_at_ms,
        })
        rest_response = self._post(self._polls_url, rest_request, {IDEMPOTENCY_KEY_HEADER: "payload-rest"})
        create_poll = PayloadComparison(
            Operation.CREATE_POLL,
            grpc_request.ByteSize(), len(rest_request),
            grpc_response.ByteSize(), len(rest_response.content),
        )
        grpc_poll_id = grpc_response.poll_id
        rest_poll_id = rest_response.json()["pollId"]
        cast_vote = self._measure_votes(grpc_poll_id, rest_poll_id, len(sample_poll.options))
        get_results = self._measure_results(grpc_poll_id, rest_poll_id)
        return [create_poll, cast_vote, get_results]

    def close(self) -> None:
        self._channel.close()
        self._session.close()

    def _measure_votes(self, grpc_poll_id: int, rest_poll_id: int, option_count: int) -> PayloadComparison:
        last_vote = None
        for voter in range(SAMPLE_VOTERS):
            user_id = FIRST_VOTER_ID + voter
            option_id = 1 + voter % option_count
            grpc_request = polls_pb2.CastVoteRequest(poll_id=grpc_poll_id, user_id=user_id, option_id=option_id)
            grpc_response = self._stub.CastVote(grpc_request)
            rest_request = to_json_bytes({"userId": user_id, "optionId": option_id})
            rest_response = self._post(f"{self._polls_url}/{rest_poll_id}/votes", rest_request, {})
            last_vote = PayloadComparison(
                Operation.CAST_VOTE,
                grpc_request.ByteSize(), len(rest_request),
                grpc_response.ByteSize(), len(rest_response.content),
            )
        return last_vote

    def _measure_results(self, grpc_poll_id: int, rest_poll_id: int) -> PayloadComparison:
        grpc_request = polls_pb2.GetResultsRequest(poll_id=grpc_poll_id)
        grpc_response = self._stub.GetResults(grpc_request)
        rest_response = self._session.get(f"{self._polls_url}/{rest_poll_id}/results")
        return PayloadComparison(
            Operation.GET_RESULTS,
            grpc_request.ByteSize(), 0,
            grpc_response.ByteSize(), len(rest_response.content),
        )

    def _post(self, url: str, body: bytes, extra_headers: dict) -> requests.Response:
        headers = {"Content-Type": "application/json", **extra_headers}
        return self._session.post(url, data=body, headers=headers)
