package com.emirchat.bench;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/**
 * Runs the benchmarks from Maven: {@code mvn -Pbench test} (about four minutes), or
 * {@code mvn -Pbench test -Dbench.args=quick} for a shorter, noisier run. Results go to
 * {@code target/jmh-result.json}; {@code src/bench/compare.py} compares two such files.
 */
class RunBenchmarksTest {
    @Test
    void run() throws Exception {
        boolean quick = System.getProperty("bench.args", "").contains("quick");
        String only = System.getProperty("bench.only", "");
        ChainedOptionsBuilder o = new OptionsBuilder()
                .include(only.isEmpty() ? "com\\.emirchat\\..*Bench\\..*" : only)
                .forks(1)
                .warmupIterations(quick ? 2 : 3).warmupTime(TimeValue.seconds(1))
                .measurementIterations(quick ? 3 : 5).measurementTime(TimeValue.seconds(quick ? 1 : 2))
                .jvmArgsAppend("-Xmx512m")
                .resultFormat(ResultFormatType.JSON)
                .result("target/jmh-result.json");
        new Runner(o.build()).run();
    }
}
