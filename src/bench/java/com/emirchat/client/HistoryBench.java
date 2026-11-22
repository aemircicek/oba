package com.emirchat.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/** The local history (encrypted at rest): storing, opening a chat, searching. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class HistoryBench {
    static final int MESSAGES = 5_000;
    private History history;
    private long sentAt = 1_700_000_000_000L;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        Path dir = Files.createTempDirectory("oba-bench-history");
        history = History.open(dir.resolve("history.db"), "bench password", 1_000);
        String[] words = {"merhaba", "akşam", "toplantı", "kamp", "ateş", "çöl", "yıldız", "kod", "test", "sürüm"};
        for (int i = 0; i < MESSAGES; i++) {
            String text = words[i % words.length] + " " + words[(i * 7) % words.length] + " mesaj " + i;
            history.add("dm:efe" + (i % 20), i % 2 == 0 ? "emir" : "efe", text, sentAt++, i % 2 == 0, History.SENT, i);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        history.close();
    }

    /** Storing one received text. */
    @Benchmark
    public long add() throws Exception {
        return history.add("dm:bench", "efe", "a new message arrives", sentAt++, false, History.RECEIVED, 0);
    }

    /** Opening a chat: its newest 50 messages. */
    @Benchmark
    public List<History.Message> openChat() throws Exception {
        return history.load("dm:efe3", 50);
    }

    /** Searching every chat for a word. */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public List<History.Message> search() throws Exception {
        return history.search("yıldız", 50);
    }

    /** Searching for something that is not there (the worst case: every message looked at). */
    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public List<History.Message> searchMiss() throws Exception {
        return history.search("bulunmayan", 50);
    }
}
