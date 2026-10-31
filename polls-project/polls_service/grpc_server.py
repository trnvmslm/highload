from concurrent.futures import ThreadPoolExecutor

import grpc

from polls_service import proto_mapper
from polls_service.api import polls_pb2, polls_pb2_grpc
from polls_service.errors import InvalidPollError, NotPollOwnerError, PollNotFoundError
from polls_service.fault import FaultInjector
from polls_service.models import VoteOutcome
from polls_service.service import PollService

WORKER_THREADS = 16
SHUTDOWN_GRACE_SECONDS = 5

VOTE_STATUSES = {
    VoteOutcome.ACCEPTED: polls_pb2.VOTE_STATUS_ACCEPTED,
    VoteOutcome.ALREADY_COUNTED: polls_pb2.VOTE_STATUS_ALREADY_COUNTED,
}

VOTE_ERRORS = {
    VoteOutcome.CONFLICT: (grpc.StatusCode.ALREADY_EXISTS, "User already voted for another option"),
    VoteOutcome.POLL_CLOSED: (grpc.StatusCode.FAILED_PRECONDITION, "Poll is closed"),
    VoteOutcome.INVALID_OPTION: (grpc.StatusCode.INVALID_ARGUMENT, "Unknown option"),
}


class PollServicer(polls_pb2_grpc.PollServiceServicer):
    def __init__(self, poll_service: PollService):
        self._poll_service = poll_service

    def CreatePoll(self, request, context):
        try:
            poll_id = self._poll_service.create_poll(request.idempotency_key, proto_mapper.to_command(request))
        except InvalidPollError as error:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(error))
        return polls_pb2.CreatePollResponse(poll_id=poll_id)

    def CastVote(self, request, context):
        try:
            outcome = self._poll_service.cast_vote(request.poll_id, request.user_id, request.option_id)
        except PollNotFoundError as error:
            context.abort(grpc.StatusCode.NOT_FOUND, str(error))
        if outcome in VOTE_ERRORS:
            status_code, message = VOTE_ERRORS[outcome]
            context.abort(status_code, message)
        return polls_pb2.CastVoteResponse(status=VOTE_STATUSES[outcome])

    def GetResults(self, request, context):
        try:
            results = self._poll_service.get_results(request.poll_id)
        except PollNotFoundError as error:
            context.abort(grpc.StatusCode.NOT_FOUND, str(error))
        return proto_mapper.to_results_response(results)

    def GetPoll(self, request, context):
        try:
            details = self._poll_service.get_poll(request.poll_id, request.user_id)
        except PollNotFoundError as error:
            context.abort(grpc.StatusCode.NOT_FOUND, str(error))
        return proto_mapper.to_poll_response(details)

    def ClosePoll(self, request, context):
        try:
            poll = self._poll_service.close_poll(request.poll_id, request.user_id)
        except PollNotFoundError as error:
            context.abort(grpc.StatusCode.NOT_FOUND, str(error))
        except NotPollOwnerError as error:
            context.abort(grpc.StatusCode.PERMISSION_DENIED, str(error))
        return polls_pb2.ClosePollResponse(ends_at_epoch_ms=poll.ends_at_ms)


class FaultInjectionInterceptor(grpc.ServerInterceptor):
    def __init__(self, fault_injector: FaultInjector):
        self._fault_injector = fault_injector
        self._rejection = grpc.unary_unary_rpc_method_handler(self._reject)

    def intercept_service(self, continuation, handler_call_details):
        if self._fault_injector.should_fail():
            return self._rejection
        return continuation(handler_call_details)

    @staticmethod
    def _reject(request, context):
        context.abort(grpc.StatusCode.UNAVAILABLE, "Injected fault")


class GrpcServer:
    def __init__(self, port: int, poll_service: PollService, fault_injector: FaultInjector):
        interceptor = FaultInjectionInterceptor(fault_injector)
        self._server = grpc.server(ThreadPoolExecutor(max_workers=WORKER_THREADS), interceptors=[interceptor])
        polls_pb2_grpc.add_PollServiceServicer_to_server(PollServicer(poll_service), self._server)
        self.port = self._server.add_insecure_port(f"[::]:{port}")

    def start(self) -> None:
        self._server.start()

    def wait_for_termination(self) -> None:
        self._server.wait_for_termination()

    def stop(self) -> None:
        self._server.stop(SHUTDOWN_GRACE_SECONDS).wait()

    def __enter__(self) -> "GrpcServer":
        self.start()
        return self

    def __exit__(self, *exc_info) -> None:
        self.stop()
