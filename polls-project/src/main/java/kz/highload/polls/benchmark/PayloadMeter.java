package kz.highload.polls.benchmark;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import kz.highload.polls.domain.CreatePollCommand;
import kz.highload.polls.grpc.ProtoMapper;
import kz.highload.polls.grpc.v1.CastVoteRequest;
import kz.highload.polls.grpc.v1.CastVoteResponse;
import kz.highload.polls.grpc.v1.CreatePollRequest;
import kz.highload.polls.grpc.v1.CreatePollResponse;
import kz.highload.polls.grpc.v1.GetResultsRequest;
import kz.highload.polls.grpc.v1.GetResultsResponse;
import kz.highload.polls.grpc.v1.PollServiceGrpc;
import kz.highload.polls.rest.PollHttpHandler;
import kz.highload.polls.rest.dto.CastVoteBody;
import kz.highload.polls.rest.dto.CreatePollBody;
import kz.highload.polls.rest.dto.CreatePollReply;

public final class PayloadMeter implements AutoCloseable {
    private static final int SAMPLE_VOTERS = 1_000;
    private static final int FIRST_VOTER_ID = 5_000_000;
    private static final long CLOSE_TIMEOUT_SECONDS = 5;

    private final ManagedChannel channel;
    private final PollServiceGrpc.PollServiceBlockingStub stub;
    private final HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String pollsUrl;

    public PayloadMeter(String host, int grpcPort, int restPort) {
        this.channel = ManagedChannelBuilder.forAddress(host, grpcPort).usePlaintext().build();
        this.stub = PollServiceGrpc.newBlockingStub(channel);
        this.pollsUrl = "http://" + host + ":" + restPort + "/v1/polls";
    }

    public List<PayloadComparison> measure(CreatePollCommand samplePoll) throws IOException, InterruptedException {
        CreatePollRequest grpcCreateRequest = ProtoMapper.toRequest("payload-grpc", samplePoll);
        CreatePollResponse grpcCreateResponse = stub.createPoll(grpcCreateRequest);
        byte[] restCreateRequest = objectMapper.writeValueAsBytes(toBody(samplePoll));
        byte[] restCreateResponse = post(pollsUrl, restCreateRequest);

        PayloadComparison createPoll = new PayloadComparison(Operation.CREATE_POLL,
                grpcCreateRequest.getSerializedSize(), restCreateRequest.length,
                grpcCreateResponse.getSerializedSize(), restCreateResponse.length);

        int grpcPollId = grpcCreateResponse.getPollId();
        int restPollId = objectMapper.readValue(restCreateResponse, CreatePollReply.class).pollId();
        int optionCount = samplePoll.options().size();

        return List.of(
                createPoll,
                measureVotes(grpcPollId, restPollId, optionCount),
                measureResults(grpcPollId, restPollId));
    }

    private PayloadComparison measureVotes(int grpcPollId, int restPollId, int optionCount)
            throws IOException, InterruptedException {
        PayloadComparison lastVote = null;
        for (int voter = 0; voter < SAMPLE_VOTERS; voter++) {
            int userId = FIRST_VOTER_ID + voter;
            int optionId = 1 + voter % optionCount;

            CastVoteRequest grpcRequest = CastVoteRequest.newBuilder()
                    .setPollId(grpcPollId).setUserId(userId).setOptionId(optionId).build();
            CastVoteResponse grpcResponse = stub.castVote(grpcRequest);
            byte[] restRequest = objectMapper.writeValueAsBytes(new CastVoteBody(userId, optionId));
            byte[] restResponse = post(pollsUrl + "/" + restPollId + "/votes", restRequest);

            lastVote = new PayloadComparison(Operation.CAST_VOTE,
                    grpcRequest.getSerializedSize(), restRequest.length,
                    grpcResponse.getSerializedSize(), restResponse.length);
        }
        return lastVote;
    }

    private PayloadComparison measureResults(int grpcPollId, int restPollId) throws IOException, InterruptedException {
        GetResultsRequest grpcRequest = GetResultsRequest.newBuilder().setPollId(grpcPollId).build();
        GetResultsResponse grpcResponse = stub.getResults(grpcRequest);
        byte[] restResponse = get(pollsUrl + "/" + restPollId + "/results");
        int restRequestBytes = 0;
        return new PayloadComparison(Operation.GET_RESULTS,
                grpcRequest.getSerializedSize(), restRequestBytes,
                grpcResponse.getSerializedSize(), restResponse.length);
    }

    private static CreatePollBody toBody(CreatePollCommand command) {
        return new CreatePollBody(command.ownerId(), command.question(), command.options(),
                command.endsAt().toEpochMilli());
    }

    private byte[] post(String url, byte[] body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header(PollHttpHandler.IDEMPOTENCY_KEY_HEADER, "payload-" + System.nanoTime())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    private byte[] get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    @Override
    public void close() throws InterruptedException {
        channel.shutdown();
        channel.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
