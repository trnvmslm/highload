import time
import uuid

import grpc
from google.protobuf.json_format import MessageToDict

from polls_service.api import polls_pb2, polls_pb2_grpc

TARGET = "127.0.0.1:50051"
OWNER_ID = 7
USER_ID = 42
OTHER_USER_ID = 43
DAY_MS = 24 * 60 * 60 * 1000


def call(title: str, method, request):
    try:
        response = method(request, timeout=2)
    except grpc.RpcError as error:
        print(f"{title}: {error.code().name} ({error.details()})")
        return None
    print(f"{title}: OK {MessageToDict(response)}")
    return response


def main() -> None:
    with grpc.insecure_channel(TARGET) as channel:
        stub = polls_pb2_grpc.PollServiceStub(channel)
        create_request = polls_pb2.CreatePollRequest(
            idempotency_key=str(uuid.uuid4()),
            owner_id=OWNER_ID,
            question="Best language?",
            options=["Java", "Python"],
            ends_at_epoch_ms=int(time.time() * 1000) + DAY_MS,
        )
        poll_id = call("CreatePoll", stub.CreatePoll, create_request).poll_id
        call("CreatePoll again (same key)", stub.CreatePoll, create_request)

        vote_java = polls_pb2.CastVoteRequest(poll_id=poll_id, user_id=USER_ID, option_id=1)
        vote_python = polls_pb2.CastVoteRequest(poll_id=poll_id, user_id=USER_ID, option_id=2)
        call("CastVote Java", stub.CastVote, vote_java)
        call("CastVote Java again", stub.CastVote, vote_java)
        call("CastVote Python", stub.CastVote, vote_python)

        call("GetResults", stub.GetResults, polls_pb2.GetResultsRequest(poll_id=poll_id))
        call("GetPoll", stub.GetPoll, polls_pb2.GetPollRequest(poll_id=poll_id, user_id=USER_ID))

        call("ClosePoll by user 42", stub.ClosePoll, polls_pb2.ClosePollRequest(poll_id=poll_id, user_id=USER_ID))
        call("ClosePoll by author", stub.ClosePoll, polls_pb2.ClosePollRequest(poll_id=poll_id, user_id=OWNER_ID))

        late_vote = polls_pb2.CastVoteRequest(poll_id=poll_id, user_id=OTHER_USER_ID, option_id=1)
        call("CastVote after close", stub.CastVote, late_vote)


if __name__ == "__main__":
    main()