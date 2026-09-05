# Invocation and authoring roadmap

The twelve implementation issues in [roadmap #15](https://github.com/timbrinded/jetbrains-prompt-templates/issues/15) are complete and merged into `main`. The work started from the [0.2.0 review baseline](https://github.com/timbrinded/jetbrains-prompt-templates/commit/b20c62be3762598c1aed5ffff0fd5756b3012049) and finished with [PR #27](https://github.com/timbrinded/jetbrains-prompt-templates/pull/27) on 5 September 2026.

The library manages templates. Quick Use invokes them through the same session, validation, rendering and delivery code. Templates remain portable Markdown with typed JSON metadata. The plugin uses native JetBrains UI and does not execute prompts or contact an agent service.

## Delivered work

Each issue had a separate PR. Each PR was merged only after exploratory testing, the full end-to-end suite, unit/build checks, compatibility verification and green CI. The next issue started after that merge.

| Issue | Delivered behavior | Merged PR |
| --- | --- | --- |
| [#3](https://github.com/timbrinded/jetbrains-prompt-templates/issues/3) | Shared invocation with frozen, inspectable context; validated preview and output agree. Explicit refresh, reload and insertion-target selection. | [#16](https://github.com/timbrinded/jetbrains-prompt-templates/pull/16) |
| [#4](https://github.com/timbrinded/jetbrains-prompt-templates/issues/4) | Recoverable two-file saves, repository revision checks and reviewed overwrite decisions. | [#17](https://github.com/timbrinded/jetbrains-prompt-templates/pull/17) |
| [#5](https://github.com/timbrinded/jetbrains-prompt-templates/issues/5) | Cancel protects changed Markdown and metadata, with Keep Editing as the default. | [#18](https://github.com/timbrinded/jetbrains-prompt-templates/pull/18) |
| [#6](https://github.com/timbrinded/jetbrains-prompt-templates/issues/6) | Responsive author controls, focus scrolling and a simpler Use footer at narrow widths and higher scaling. | [#19](https://github.com/timbrinded/jetbrains-prompt-templates/pull/19) |
| [#7](https://github.com/timbrinded/jetbrains-prompt-templates/issues/7) | Use forms contain referenced inputs only; validation focuses the actual multiline input. | [#20](https://github.com/timbrinded/jetbrains-prompt-templates/pull/20) |
| [#8](https://github.com/timbrinded/jetbrains-prompt-templates/issues/8) | Keyboard Quick Use, ranked search, identity-based favourites/recents and shared tool-window handoff. | [#21](https://github.com/timbrinded/jetbrains-prompt-templates/pull/21) |
| [#9](https://github.com/timbrinded/jetbrains-prompt-templates/issues/9) | Independent template duplication and immutable selection capture, with explicit placeholder interpretation. | [#22](https://github.com/timbrinded/jetbrains-prompt-templates/pull/22) |
| [#10](https://github.com/timbrinded/jetbrains-prompt-templates/issues/10) | Authored defaults, input presentation, field ordering and reset controls that retain context. | [#23](https://github.com/timbrinded/jetbrains-prompt-templates/pull/23) |
| [#11](https://github.com/timbrinded/jetbrains-prompt-templates/issues/11) | Insert Variable and Extract as Variable, with explicit default capture and coordinated native Undo/Redo. | [#24](https://github.com/timbrinded/jetbrains-prompt-templates/pull/24) |
| [#12](https://github.com/timbrinded/jetbrains-prompt-templates/issues/12) | Designed and implemented explicit file-buffer and Git-diff attachments, with visible provenance, limits and frozen text. | [#25](https://github.com/timbrinded/jetbrains-prompt-templates/pull/25) |
| [#13](https://github.com/timbrinded/jetbrains-prompt-templates/issues/13) | Export the validated preview to a new, independent local Markdown scratch file. | [#26](https://github.com/timbrinded/jetbrains-prompt-templates/pull/26) |
| [#14](https://github.com/timbrinded/jetbrains-prompt-templates/issues/14) | Three optional local worked examples, mock previews and explicitly added editable copies. | [#27](https://github.com/timbrinded/jetbrains-prompt-templates/pull/27) |

## Validation and operating limits

The final feature commit, [`166d79b`](https://github.com/timbrinded/jetbrains-prompt-templates/commit/166d79b0a64717d429dac7b616741fa3b011827c), passed 153 unit tests and all 23 end-to-end scenarios with no failures or skipped tests. The real-IDE suite uses isolated WebStorm 2026.2 instances. Plugin Verifier checks RustRover 2026.2 and WebStorm 2026.2. These are distinct checks; they do not establish runtime coverage for every compatible IDE or operating system.

The [README](../README.md#build-and-run) explains full-suite execution, native exploratory sessions and the required `signoff/e2e` status. PR descriptions record validation for each delivered change. The [Quick Use benchmark](quick-use-benchmark.md) records the 500-template workload and review targets.

Ordinary entered values and captured context stay in memory. Authored defaults are saved template configuration. Explicit Copy, Insert, export and scratch actions deliberately deliver output; scratch files persist outside the template library. Favourites and recents store library paths, UUIDs and ordering only. See [Privacy](../PRIVACY.md).

File and Git attachments are optional. Git capture requires the Git plugin, an explicit repository and staged/unstaged scope; it does not run a terminal or contact a remote. The [attachment design](context-attachments.md) states supported sources, size limits and failure behavior. [Worked examples](worked-examples.md) explain the mock fixtures and exact expected output.

This completes the issue #15 scope. It does not declare version 1.0 complete or publish a release. The [original implementation plan](implementation-plan.md) retains broader release and compatibility work. Project-local libraries, remote sync, conditional templates, agent-window integration and prompt execution remain outside this roadmap.
