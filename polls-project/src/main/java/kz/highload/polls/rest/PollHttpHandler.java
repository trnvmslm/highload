package kz.highload.polls.rest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import kz.highload.polls.domain.CreatePollCommand;
import kz.highload.polls.domain.InvalidPollException;
import kz.highload.polls.domain.NotPollOwnerException;
import kz.highload.polls.domain.Poll;
import kz.highload.polls.domain.PollDetails;
import kz.highload.polls.domain.PollNotFoundException;
import kz.highload.polls.domain.PollResults;
import kz.highload.polls.domain.PollService;
import kz.highload.polls.domain.VoteOutcome;
import kz.highload.polls.fault.FaultInjector;
import kz.highload.polls.rest.dto.CastVoteBody;
import kz.highload.polls.rest.dto.CastVoteReply;
import kz.highload.polls.rest.dto.ClosePollBody;
import kz.highload.polls.rest.dto.ClosePollReply;
import kz.highload.polls.rest.dto.CreatePollBody;
import kz.highload.polls.rest.dto.CreatePollReply;
import kz.highload.polls.rest.dto.ErrorReply;
import kz.highload.polls.rest.dto.PollReply;

public class PollHttpHandler implements HttpHandler {
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final int STATUS_OK = 200;
    private static final int STATUS_CREATED = 201;
    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_FORBIDDEN = 403;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int STATUS_METHOD_NOT_ALLOWED = 405;
    private static final int STATUS_CONFLICT = 409;
    private static final int STATUS_GONE = 410;
    private static final int STATUS_SERVICE_UNAVAILABLE = 503;

    private static final int COLLECTION_PATH_SEGMENTS = 3;
    private static final int POLL_PATH_SEGMENTS = 4;
    private static final int SUB_RESOURCE_PATH_SEGMENTS = 5;
    private static final int POLL_ID_SEGMENT = 3;
    private static final int SUB_RESOURCE_SEGMENT = 4;
    private static final String USER_ID_PARAMETER = "userId=";

    private final PollService pollService;
    private final FaultInjector faultInjector;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PollHttpHandler(PollService pollService, FaultInjector faultInjector) {
        this.pollService = pollService;
        this.faultInjector = faultInjector;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (faultInjector.shouldFail()) {
                sendJson(exchange, STATUS_SERVICE_UNAVAILABLE, new ErrorReply("Injected fault"));
                return;
            }
            route(exchange);
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String[] segments = exchange.getRequestURI().getPath().split("/");
        try {
            if (segments.length == COLLECTION_PATH_SEGMENTS && "POST".equals(method)) {
                handleCreatePoll(exchange);
            } else if (isSubResource(segments, "votes") && "POST".equals(method)) {
                handleCastVote(exchange, Integer.parseInt(segments[POLL_ID_SEGMENT]));
            } else if (isSubResource(segments, "results") && "GET".equals(method)) {
                handleGetResults(exchange, Integer.parseInt(segments[POLL_ID_SEGMENT]));
            } else if (segments.length == POLL_PATH_SEGMENTS && "GET".equals(method)) {
                handleGetPoll(exchange, Integer.parseInt(segments[POLL_ID_SEGMENT]));
            } else if (isSubResource(segments, "close") && "POST".equals(method)) {
                handleClosePoll(exchange, Integer.parseInt(segments[POLL_ID_SEGMENT]));
            } else {
                sendJson(exchange, STATUS_METHOD_NOT_ALLOWED, new ErrorReply("Unsupported route"));
            }
        } catch (PollNotFoundException e) {
            sendJson(exchange, STATUS_NOT_FOUND, new ErrorReply(e.getMessage()));
        } catch (NotPollOwnerException e) {
            sendJson(exchange, STATUS_FORBIDDEN, new ErrorReply(e.getMessage()));
        } catch (InvalidPollException | NumberFormatException | JacksonException e) {
            sendJson(exchange, STATUS_BAD_REQUEST, new ErrorReply(e.getMessage()));
        }
    }

    private void handleCreatePoll(HttpExchange exchange) throws IOException {
        CreatePollBody body = readJson(exchange, CreatePollBody.class);
        CreatePollCommand command = new CreatePollCommand(
                body.ownerId(), body.question(), body.options(), Instant.ofEpochMilli(body.endsAtEpochMs()));
        String idempotencyKey = exchange.getRequestHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
        int pollId = pollService.createPoll(idempotencyKey, command);
        sendJson(exchange, STATUS_CREATED, new CreatePollReply(pollId));
    }

    private void handleCastVote(HttpExchange exchange, int pollId) throws IOException {
        CastVoteBody body = readJson(exchange, CastVoteBody.class);
        VoteOutcome outcome = pollService.castVote(pollId, body.userId(), body.optionId());
        switch (outcome) {
            case ACCEPTED -> sendJson(exchange, STATUS_CREATED, new CastVoteReply(outcome.name()));
            case ALREADY_COUNTED -> sendJson(exchange, STATUS_OK, new CastVoteReply(outcome.name()));
            case CONFLICT -> sendJson(exchange, STATUS_CONFLICT, new ErrorReply("User already voted for another option"));
            case POLL_CLOSED -> sendJson(exchange, STATUS_GONE, new ErrorReply("Poll is closed"));
            case INVALID_OPTION -> sendJson(exchange, STATUS_BAD_REQUEST, new ErrorReply("Unknown option"));
        }
    }

    private void handleGetResults(HttpExchange exchange, int pollId) throws IOException {
        PollResults results = pollService.getResults(pollId);
        sendJson(exchange, STATUS_OK, results);
    }

    private void handleGetPoll(HttpExchange exchange, int pollId) throws IOException {
        int userId = userIdFromQuery(exchange.getRequestURI().getQuery());
        PollDetails details = pollService.getPoll(pollId, userId);
        sendJson(exchange, STATUS_OK, PollReply.from(details));
    }

    private void handleClosePoll(HttpExchange exchange, int pollId) throws IOException {
        ClosePollBody body = readJson(exchange, ClosePollBody.class);
        Poll poll = pollService.closePoll(pollId, body.userId());
        sendJson(exchange, STATUS_OK, new ClosePollReply(poll.endsAt().toEpochMilli()));
    }

    private static int userIdFromQuery(String query) {
        if (query == null) {
            return 0;
        }
        for (String parameter : query.split("&")) {
            if (parameter.startsWith(USER_ID_PARAMETER)) {
                return Integer.parseInt(parameter.substring(USER_ID_PARAMETER.length()));
            }
        }
        return 0;
    }

    private static boolean isSubResource(String[] segments, String name) {
        return segments.length == SUB_RESOURCE_PATH_SEGMENTS && name.equals(segments[SUB_RESOURCE_SEGMENT]);
    }

    private <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            return objectMapper.readValue(body, type);
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
