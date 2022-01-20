# simpleperf-cleanup

Run with:

```bash
./gradlew app:run --args="PATH/TO/TRACE.trace"
```

This will generate a new trace file named `PATH/TO/TRACE-fixed.trace`. Any main thread sample
that wasn't rooted in the same stack frame as the first encountered will be "fixed": it's
callchain will be prepended with the common callchain shared by the previous and the next valid
sample.