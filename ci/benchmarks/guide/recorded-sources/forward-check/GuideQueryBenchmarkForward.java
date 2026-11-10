package com.nuvio.bench;

public final class GuideQueryBenchmarkForward {
    public static void main(String[] args) throws Exception {
        System.setProperty("nuvio.benchmark.forwardOnly", "true");
        GuideQueryBenchmark.main(args);
    }
}
