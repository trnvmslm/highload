package kz.highload.polls.benchmark;

public enum Operation {
    CREATE_POLL("CreatePoll"),
    CAST_VOTE("CastVote"),
    GET_RESULTS("GetResults");

    private final String displayName;

    Operation(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
