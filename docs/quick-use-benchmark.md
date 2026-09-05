# Quick Use search benchmark

The Starter/Driver scenario `large library search is measured in the supported IDE`
creates 500 template packages in 10 folders, with approximately 1.5 KB Markdown
bodies. It measures opening Quick Use after the IDE is ready, then name, body and
folder searches. Each measurement includes Driver RPC and polling overhead.

On 5 September 2026, WebStorm `262.8665.259` on Linux x86-64 under Xvfb, with a
2 GiB IDE heap, produced these results:

| Operation | Observed time |
| --- | ---: |
| Open and display all 500 templates | 1.54 s |
| Exact name: `Template 499` | 83 ms |
| Body: `marker-123` | 64 ms |
| Folder: `group-7` (50 results) | 84 ms |

Use **3 seconds to open** and **250 ms per query** as regression review targets
for this fixture on a comparable host. These allow margin over the measurements;
they are not latency guarantees. Test assertions check complete, correctly ranked
results. Timings are recorded separately so host load does not create flaky tests.

Run the scenario with a working display:

```sh
./gradlew :plugin:integrationTest --tests '*QuickUseIdeTest*large library*'
```

The report is written to
`plugin/build/ui-test/quick-search-benchmark-*/evidence/quick-use-benchmark.txt`.
Library scanning, metadata/body loading and query ranking run off the UI thread.
