package kz.highload.polls.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.highload.polls.domain.CreatePollCommand;
import kz.highload.polls.domain.PollResults;
import kz.highload.polls.domain.VoteOutcome;
import kz.highload.polls.rest.PollHttpHandler;
import kz.highload.polls.rest.dto.CastVoteBody;
import kz.highload.polls.rest.dto.CreatePollBody;
import kz.highload.polls.rest.dto.CreatePollReply;

public final class RestPollClient implements PollClient {
    private static final int STATUS_OK = 200;
    private static final int STATUS_CREATED = 201;
    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_CONFLICT = 409;
    private static final int STATUS_GONE = 410;
    private static final int STATUS_SERVICE_UNAVAILABLE = 503;

    private final HttpClient httpClient;
    private final String pollsUrl;
    private final Duration timeout;
    private final RetryExecutor retryExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RestPollClient(String host, int port, ClientSettings settings) {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.timeout())
                .build();
        this.pollsUrl = "http://" + host + ":" + port + "/v1/polls";
        this.timeout = settings.timeout();
        this.retryExecutor = new RetryExecutor(settings.retryPolicy());
    }

    @Override
    public int createPoll(String idempotencyKey, CreatePollCommand command) {
        CreatePollBody body = new CreatePollBody(
                command.ownerId(), command.question(), command.options(), command.endsAt().toEpochMilli());
        HttpRequest.Builder builder = jsonRequest(pollsUrl).POST(jsonBody(body));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.header(PollHttpHandler.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        HttpResponse<byte[]> response = sendWithRetries(builder.build());
        if (response.statusCode() != STATUS_CREATED) {
            throw unexpectedStatus("CreatePoll", response);
        }
        return parse(response, CreatePollReply.class).pollId();
    }

    @Override
    public VoteOutcome castVote(int pollId, int userId, int optionId) {
        String url = pollsUrl + "/" + pollId + "/votes";
        HttpRequest request = jsonRequest(url).POST(jsonBody(new CastVoteBody(userId, optionId))).build();
        HttpResponse<byte[]> response = sendWithRetries(request);
        return switch (response.statusCode()) {
            case STATUS_CREATED -> VoteOutcome.ACCEPTED;
            case STATUS_OK -> VoteOutcome.ALREADY_COUNTED;
            case STATUS_CONFLICT -> VoteOutcome.CONFLICT;
            case STATUS_GONE -> VoteOutcome.POLL_CLOSED;
            case STATUS_BAD_REQUEST -> VoteOutcome.INVALID_OPTION;
            default -> throw unexpectedStatus("CastVote", response);
        };
    }

    @Override
    public PollResults getResults(int pollId) {
        String url = pollsUrl + "/" + pollId + "/results";
        HttpRequest request = jsonRequest(url).GET().build();
        HttpResponse<byte[]> response = sendWithRetries(request);
        if (response.statusCode() != STATUS_OK) {
            throw unexpectedStatus("GetResults", response);
        }
        return parse(response, PollResults.class);
    }

    @Override
    public String protocolName() {
        return "REST/JSON";
    }

    @Override
    public void close() {
    }

    private HttpRequest.Builder jsonRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
    }

    private HttpRequest.BodyPublisher jsonBody(Object body) {
        try {
            return HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(body));
        } catch (IOException e) {
            throw new PollClientException("Cannot serialize request body", e);
        }
    }

    private HttpResponse<byte[]> sendWithRetries(HttpRequest request) {
        return retryExecutor.execute(() -> sendOnce(request));
    }

    private HttpResponse<byte[]> sendOnce(HttpRequest request) throws IOException {
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == STATUS_SERVICE_UNAVAILABLE) {
                throw new RetryableStatusException(response.statusCode());
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PollClientException("Interrupted while waiting for the response", e);
        }
    }

    private <T> T parse(HttpResponse<byte[]> response, Class<T> type) {
        try {
            return objectMapper.readValue(response.body(), type);
        } catch (IOException e) {
            throw new PollClientException("Cannot parse response body", e);
        }
    }

    private static PollClientException unexpectedStatus(String operation, HttpResponse<byte[]> response) {
        return new PollClientException(operation + " failed with HTTP " + response.statusCode());
    }
}
