import json

import grpc

from polls_service import proto_mapper
from polls_service.api import polls_pb2, polls_pb2_grpc
from polls_service.errors import PollClientError
from polls_service.models import CreatePollCommand, PollResults, VoteOutcome
from polls_service.retry import ClientSettings, RetryPolicy

SERVICE_NAME = "polls.v1.PollService"

VOTE_STATUSES = {
    polls_pb2.VOTE_STATUS_ACCEPTED: VoteOutcome.ACCEPTED,
    polls_pb2.VOTE_STATUS_ALREADY_COUNTED: VoteOutcome.ALREADY_COUNTED,
}

VOTE_ERRORS = {
    grpc.StatusCode.ALREADY_EXISTS: VoteOutcome.CONFLICT,
    grpc.StatusCode.FAILED_PRECONDITION: VoteOutcome.POLL_CLOSED,
    grpc.StatusCode.INVALID_ARGUMENT: VoteOutcome.INVALID_OPTION,
}


def retry_service_config(policy: RetryPolicy) -> str:
    retry_policy = {
        "maxAttempts": policy.max_attempts,
        "initialBackoff": f"{policy.initial_backoff_s}s",
        "maxBackoff": f"{policy.max_backoff_s}s",
        "backoffMultiplier": policy.backoff_multiplier,
        "retryableStatusCodes": ["UNAVAILABLE"],
    }
    method_config = {"name": [{"service": SERVICE_NAME}], "retryPolicy": retry_policy}
    return json.dumps({"methodConfig": [method_config]})


class GrpcPollClient:
    protocol_name = "gRPC"

    def __init__(self, host: str, port: int, settings: ClientSettings = ClientSettings()):
        options = [
            ("grpc.enable_retries", 1),
            ("grpc.service_config", retry_service_config(settings.retry_policy)),
        ]
        self._channel = grpc.insecure_channel(f"{host}:{port}", options=options)
        self._stub = polls_pb2_grpc.PollServiceStub(self._channel)
        self._timeout_s = settings.timeout_s

    def create_poll(self, idempotency_key: str, command: CreatePollCommand) -> int:
        request = proto_mapper.to_create_request(idempotency_key, command)
        try:
            return self._stub.CreatePoll(request, timeout=self._timeout_s).poll_id
        except grpc.RpcError as error:
            raise PollClientError(f"CreatePoll failed with {error.code().name}") from error

    def cast_vote(self, poll_id: int, user_id: int, option_id: int) -> VoteOutcome:
        request = polls_pb2.CastVoteRequest(poll_id=poll_id, user_id=user_id, option_id=option_id)
        try:
            response = self._stub.CastVote(request, timeout=self._timeout_s)
        except grpc.RpcError as error:
            if error.code() in VOTE_ERRORS:
                return VOTE_ERRORS[error.code()]
            raise PollClientError(f"CastVote failed with {error.code().name}") from error
        return VOTE_STATUSES[response.status]

    def get_results(self, poll_id: int) -> PollResults:
        request = polls_pb2.GetResultsRequest(poll_id=poll_id)
        try:
            response = self._stub.GetResults(request, timeout=self._timeout_s)
        except grpc.RpcError as error:
            raise PollClientError(f"GetResults failed with {error.code().name}") from error
        return proto_mapper.to_results(response)

    def close(self) -> None:
        self._channel.close()
