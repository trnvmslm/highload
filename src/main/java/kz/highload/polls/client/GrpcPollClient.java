package kz.highload.polls.client;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import kz.highload.polls.domain.CreatePollCommand;
import kz.highload.polls.domain.PollResults;
import kz.highload.polls.domain.VoteOutcome;
import kz.highload.polls.grpc.ProtoMapper;
import kz.highload.polls.grpc.v1.CastVoteRequest;
import kz.highload.polls.grpc.v1.CastVoteResponse;
import kz.highload.polls.grpc.v1.CreatePollRequest;
import kz.highload.polls.grpc.v1.GetResultsRequest;
import kz.highload.polls.grpc.v1.GetResultsResponse;
import kz.highload.polls.grpc.v1.PollServiceGrpc;
import kz.highload.polls.grpc.v1.VoteStatus;

public final class GrpcPollClient implements PollClient {
    private static final String SERVICE_NAME = "polls.v1.PollService";
    private static final long CLOSE_TIMEOUT_SECONDS = 5;

    private final ManagedChannel channel;
    private final PollServiceGrpc.PollServiceBlockingStub stub;
    private final Duration timeout;

    public GrpcPollClient(String host, int port, ClientSettings settings) {
        RetryPolicy retryPolicy = settings.retryPolicy();
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .defaultServiceConfig(retryServiceConfig(retryPolicy))
                .enableRetry()
                .maxRetryAttempts(retryPolicy.maxAttempts())
                .build();
        this.stub = PollServiceGrpc.newBlockingStub(channel);
        this.timeout = settings.timeout();
    }

    @Override
    public int createPoll(String idempotencyKey, CreatePollCommand command) {
        CreatePollRequest request = ProtoMapper.toRequest(idempotencyKey, command);
        try {
            return stubWithDeadline().createPoll(request).getPollId();
        } catch (StatusRuntimeException e) {
            throw callFailed("CreatePoll", e);
        }
    }

    @Override
    public VoteOutcome castVote(int pollId, int userId, int optionId) {
        CastVoteRequest request = CastVoteRequest.newBuilder()
                .setPollId(pollId)
                .setUserId(userId)
                .setOptionId(optionId)
                .build();
        try {
            CastVoteResponse response = stubWithDeadline().castVote(request);
            return outcomeOf(response.getStatus());
        } catch (StatusRuntimeException e) {
            return outcomeOf(e);
        }
    }

    @Override
    public PollResults getResults(int pollId) {
        GetResultsRequest request = GetResultsRequest.newBuilder().setPollId(pollId).build();
        try {
            GetResultsResponse response = stubWithDeadline().getResults(request);
            return ProtoMapper.toResults(response);
        } catch (StatusRuntimeException e) {
            throw callFailed("GetResults", e);
        }
    }

    @Override
    public String protocolName() {
        return "gRPC";
    }

    @Override
    public void close() {
        channel.shutdown();
        try {
            channel.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }

    private PollServiceGrpc.PollServiceBlockingStub stubWithDeadline() {
        return stub.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static VoteOutcome outcomeOf(VoteStatus status) {
        if (status == VoteStatus.VOTE_STATUS_ACCEPTED) {
            return VoteOutcome.ACCEPTED;
        }
        return VoteOutcome.ALREADY_COUNTED;
    }

    private static VoteOutcome outcomeOf(StatusRuntimeException error) {
        Status.Code code = error.getStatus().getCode();
        return switch (code) {
            case ALREADY_EXISTS -> VoteOutcome.CONFLICT;
            case FAILED_PRECONDITION -> VoteOutcome.POLL_CLOSED;
            case INVALID_ARGUMENT -> VoteOutcome.INVALID_OPTION;
            default -> throw callFailed("CastVote", error);
        };
    }

    private static PollClientException callFailed(String method, StatusRuntimeException error) {
        return new PollClientException(method + " failed with " + error.getStatus().getCode(), error);
    }

    private static Map<String, Object> retryServiceConfig(RetryPolicy policy) {
        Map<String, Object> retryPolicy = Map.of(
                "maxAttempts", (double) policy.maxAttempts(),
                "initialBackoff", toGrpcDuration(policy.initialBackoff()),
                "maxBackoff", toGrpcDuration(policy.maxBackoff()),
                "backoffMultiplier", policy.backoffMultiplier(),
                "retryableStatusCodes", List.of(Status.Code.UNAVAILABLE.name()));
        Map<String, Object> methodConfig = Map.of(
                "name", List.of(Map.of("service", SERVICE_NAME)),
                "retryPolicy", retryPolicy);
        return Map.of("methodConfig", List.of(methodConfig));
    }

    private static String toGrpcDuration(Duration duration) {
        return String.format(Locale.ROOT, "%.3fs", duration.toMillis() / 1000.0);
    }
}
