# Benchmarks

JMH micro-benchmarks of the paths that decide how the app feels: end-to-end encryption of a
direct message, a group message and a voice packet; storing, loading and searching the encrypted
history; the wire format; Opus. They live outside `src/test` so a normal build does not compile them.

```sh
mvn -Pbench test                          # all of them, about four minutes; results in target/jmh-result.json
mvn -Pbench test -Dbench.args=quick       # shorter and noisier
mvn -Pbench test -Dbench.only='.*HistoryBench.*'
python3 src/bench/compare.py before.json after.json
```

On pull requests CI runs them (quick) on the base and on the change, on the same machine, and fails
if one became more than 30 % slower beyond the noise; the table is in the run's summary.
