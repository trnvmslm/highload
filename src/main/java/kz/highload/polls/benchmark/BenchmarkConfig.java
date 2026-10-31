package kz.highload.polls.benchmark;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record BenchmarkConfig(int requests, int warmup, List<Integer> threadCounts, double failureRate,
                              Path outputDir) {
    private static final String PREFIX = "--";

    public static BenchmarkConfig fromArgs(String[] args) {
        Map<String, String> options = parseOptions(args);
        return new BenchmarkConfig(
                Integer.parseInt(options.getOrDefault("requests", "5000")),
                Integer.parseInt(options.getOrDefault("warmup", "2000")),
                parseThreadCounts(options.getOrDefault("threads", "1,16")),
                Double.parseDouble(options.getOrDefault("failure-rate", "0.0")),
                Path.of(options.getOrDefault("output", "results")));
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            int separator = arg.indexOf('=');
            if (!arg.startsWith(PREFIX) || separator < 0) {
                throw new IllegalArgumentException("Expected --name=value but got: " + arg);
            }
            options.put(arg.substring(PREFIX.length(), separator), arg.substring(separator + 1));
        }
        return options;
    }

    private static List<Integer> parseThreadCounts(String value) {
        List<Integer> threadCounts = new ArrayList<>();
        for (String part : value.split(",")) {
            threadCounts.add(Integer.parseInt(part.trim()));
        }
        return threadCounts;
    }
}
