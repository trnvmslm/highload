package kz.highload.polls.benchmark;

public record PayloadComparison(Operation operation, int grpcRequestBytes, int restRequestBytes,
                                int grpcResponseBytes, int restResponseBytes) {
    public double requestSavingPercent() {
        return savingPercent(grpcRequestBytes, restRequestBytes);
    }

    public double responseSavingPercent() {
        return savingPercent(grpcResponseBytes, restResponseBytes);
    }

    private static double savingPercent(int grpcBytes, int restBytes) {
        if (restBytes == 0) {
            return 0.0;
        }
        return 100.0 * (restBytes - grpcBytes) / restBytes;
    }
}
