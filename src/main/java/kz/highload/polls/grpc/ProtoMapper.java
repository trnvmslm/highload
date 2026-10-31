package kz.highload.polls.grpc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import kz.highload.polls.domain.CreatePollCommand;
import kz.highload.polls.domain.OptionCount;
import kz.highload.polls.domain.Poll;
import kz.highload.polls.domain.PollDetails;
import kz.highload.polls.domain.PollResults;
import kz.highload.polls.grpc.v1.CreatePollRequest;
import kz.highload.polls.grpc.v1.GetPollResponse;
import kz.highload.polls.grpc.v1.GetResultsResponse;
import kz.highload.polls.grpc.v1.OptionResult;

public final class ProtoMapper {
    private ProtoMapper() {
    }

    public static CreatePollRequest toRequest(String idempotencyKey, CreatePollCommand command) {
        String key = idempotencyKey == null ? "" : idempotencyKey;
        return CreatePollRequest.newBuilder()
                .setIdempotencyKey(key)
                .setOwnerId(command.ownerId())
                .setQuestion(command.question())
                .addAllOptions(command.options())
                .setEndsAtEpochMs(command.endsAt().toEpochMilli())
                .build();
    }

    public static CreatePollCommand toCommand(CreatePollRequest request) {
        Instant endsAt = Instant.ofEpochMilli(request.getEndsAtEpochMs());
        return new CreatePollCommand(request.getOwnerId(), request.getQuestion(), request.getOptionsList(), endsAt);
    }

    public static GetResultsResponse toResponse(PollResults results) {
        GetResultsResponse.Builder builder = GetResultsResponse.newBuilder()
                .setPollId(results.pollId())
                .setTotalVotes(results.totalVotes());
        for (OptionCount option : results.options()) {
            builder.addOptions(toOptionResult(option));
        }
        return builder.build();
    }

    public static GetPollResponse toResponse(PollDetails details) {
        Poll poll = details.poll();
        return GetPollResponse.newBuilder()
                .setPollId(poll.id())
                .setOwnerId(poll.ownerId())
                .setQuestion(poll.question())
                .addAllOptions(poll.options())
                .setEndsAtEpochMs(poll.endsAt().toEpochMilli())
                .setOpen(details.open())
                .setMyOptionId(details.myOptionId())
                .build();
    }

    public static PollResults toResults(GetResultsResponse response) {
        List<OptionCount> options = new ArrayList<>(response.getOptionsCount());
        for (OptionResult option : response.getOptionsList()) {
            options.add(new OptionCount(option.getOptionId(), option.getText(), option.getVotes(), option.getPercent()));
        }
        return new PollResults(response.getPollId(), response.getTotalVotes(), options);
    }

    private static OptionResult toOptionResult(OptionCount option) {
        return OptionResult.newBuilder()
                .setOptionId(option.optionId())
                .setText(option.text())
                .setVotes(option.votes())
                .setPercent(option.percent())
                .build();
    }
}
