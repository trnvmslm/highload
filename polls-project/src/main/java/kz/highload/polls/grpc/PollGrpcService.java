package kz.highload.polls.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import kz.highload.polls.domain.CreatePollCommand;
import kz.highload.polls.domain.InvalidPollException;
import kz.highload.polls.domain.NotPollOwnerException;
import kz.highload.polls.domain.Poll;
import kz.highload.polls.domain.PollDetails;
import kz.highload.polls.domain.PollNotFoundException;
import kz.highload.polls.domain.PollResults;
import kz.highload.polls.domain.PollService;
import kz.highload.polls.domain.VoteOutcome;
import kz.highload.polls.grpc.v1.CastVoteRequest;
import kz.highload.polls.grpc.v1.CastVoteResponse;
import kz.highload.polls.grpc.v1.ClosePollRequest;
import kz.highload.polls.grpc.v1.ClosePollResponse;
import kz.highload.polls.grpc.v1.CreatePollRequest;
import kz.highload.polls.grpc.v1.CreatePollResponse;
import kz.highload.polls.grpc.v1.GetPollRequest;
import kz.highload.polls.grpc.v1.GetPollResponse;
import kz.highload.polls.grpc.v1.GetResultsRequest;
import kz.highload.polls.grpc.v1.GetResultsResponse;
import kz.highload.polls.grpc.v1.PollServiceGrpc;
import kz.highload.polls.grpc.v1.VoteStatus;

public class PollGrpcService extends PollServiceGrpc.PollServiceImplBase {
    private final PollService pollService;

    public PollGrpcService(PollService pollService) {
        this.pollService = pollService;
    }

    @Override
    public void createPoll(CreatePollRequest request, StreamObserver<CreatePollResponse> responseObserver) {
        try {
            CreatePollCommand command = ProtoMapper.toCommand(request);
            int pollId = pollService.createPoll(request.getIdempotencyKey(), command);
            reply(responseObserver, CreatePollResponse.newBuilder().setPollId(pollId).build());
        } catch (InvalidPollException e) {
            fail(responseObserver, Status.INVALID_ARGUMENT, e.getMessage());
        }
    }

    @Override
    public void castVote(CastVoteRequest request, StreamObserver<CastVoteResponse> responseObserver) {
        try {
            VoteOutcome outcome = pollService.castVote(request.getPollId(), request.getUserId(), request.getOptionId());
            sendVoteOutcome(responseObserver, outcome);
        } catch (PollNotFoundException e) {
            fail(responseObserver, Status.NOT_FOUND, e.getMessage());
        }
    }

    @Override
    public void getResults(GetResultsRequest request, StreamObserver<GetResultsResponse> responseObserver) {
        try {
            PollResults results = pollService.getResults(request.getPollId());
            reply(responseObserver, ProtoMapper.toResponse(results));
        } catch (PollNotFoundException e) {
            fail(responseObserver, Status.NOT_FOUND, e.getMessage());
        }
    }

    @Override
    public void getPoll(GetPollRequest request, StreamObserver<GetPollResponse> responseObserver) {
        try {
            PollDetails details = pollService.getPoll(request.getPollId(), request.getUserId());
            reply(responseObserver, ProtoMapper.toResponse(details));
        } catch (PollNotFoundException e) {
            fail(responseObserver, Status.NOT_FOUND, e.getMessage());
        }
    }

    @Override
    public void closePoll(ClosePollRequest request, StreamObserver<ClosePollResponse> responseObserver) {
        try {
            Poll poll = pollService.closePoll(request.getPollId(), request.getUserId());
            long endsAt = poll.endsAt().toEpochMilli();
            reply(responseObserver, ClosePollResponse.newBuilder().setEndsAtEpochMs(endsAt).build());
        } catch (PollNotFoundException e) {
            fail(responseObserver, Status.NOT_FOUND, e.getMessage());
        } catch (NotPollOwnerException e) {
            fail(responseObserver, Status.PERMISSION_DENIED, e.getMessage());
        }
    }

    private static void sendVoteOutcome(StreamObserver<CastVoteResponse> responseObserver, VoteOutcome outcome) {
        switch (outcome) {
            case ACCEPTED -> reply(responseObserver, voteResponse(VoteStatus.VOTE_STATUS_ACCEPTED));
            case ALREADY_COUNTED -> reply(responseObserver, voteResponse(VoteStatus.VOTE_STATUS_ALREADY_COUNTED));
            case CONFLICT -> fail(responseObserver, Status.ALREADY_EXISTS, "User already voted for another option");
            case POLL_CLOSED -> fail(responseObserver, Status.FAILED_PRECONDITION, "Poll is closed");
            case INVALID_OPTION -> fail(responseObserver, Status.INVALID_ARGUMENT, "Unknown option");
        }
    }

    private static CastVoteResponse voteResponse(VoteStatus status) {
        return CastVoteResponse.newBuilder().setStatus(status).build();
    }

    private static <T> void reply(StreamObserver<T> responseObserver, T response) {
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private static void fail(StreamObserver<?> responseObserver, Status status, String description) {
        responseObserver.onError(status.withDescription(description).asRuntimeException());
    }
}
