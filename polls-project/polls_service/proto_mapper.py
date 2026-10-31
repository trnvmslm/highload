from polls_service.api import polls_pb2
from polls_service.models import CreatePollCommand, OptionCount, PollDetails, PollResults


def to_create_request(idempotency_key: str, command: CreatePollCommand) -> polls_pb2.CreatePollRequest:
    return polls_pb2.CreatePollRequest(
        idempotency_key=idempotency_key,
        owner_id=command.owner_id,
        question=command.question,
        options=command.options,
        ends_at_epoch_ms=command.ends_at_ms,
    )


def to_command(request: polls_pb2.CreatePollRequest) -> CreatePollCommand:
    return CreatePollCommand(request.owner_id, request.question, tuple(request.options), request.ends_at_epoch_ms)


def to_results_response(results: PollResults) -> polls_pb2.GetResultsResponse:
    options = [
        polls_pb2.OptionResult(option_id=option.option_id, text=option.text, votes=option.votes, percent=option.percent)
        for option in results.options
    ]
    return polls_pb2.GetResultsResponse(poll_id=results.poll_id, total_votes=results.total_votes, options=options)


def to_results(response: polls_pb2.GetResultsResponse) -> PollResults:
    options = tuple(
        OptionCount(option.option_id, option.text, option.votes, option.percent)
        for option in response.options
    )
    return PollResults(response.poll_id, response.total_votes, options)


def to_poll_response(details: PollDetails) -> polls_pb2.GetPollResponse:
    poll = details.poll
    return polls_pb2.GetPollResponse(
        poll_id=poll.id,
        owner_id=poll.owner_id,
        question=poll.question,
        options=poll.options,
        ends_at_epoch_ms=poll.ends_at_ms,
        open=details.is_open,
        my_option_id=details.my_option_id,
    )
