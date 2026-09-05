# Explicit context attachments

Implementation design for [issue #12](https://github.com/timbrinded/jetbrains-prompt-templates/issues/12).

## Placement and ownership

Attachments enter the template only through `{{ide.attachments}}`. The author can insert it with the existing variable chooser. **Add Context…** is available from the Use view and Quick Use. If the template has no attachment placeholder, the action explains how to add one and captures nothing.

Considered alternatives were appending a context section after rendering, binding attachments to an arbitrary input, and a reserved context placeholder. The placeholder makes placement explicit and reuses the existing provider map and strict renderer. It requires an author edit to enable attachments. Appending outside the renderer would break the inspected-output contract; input binding would mix typed user values with capture ownership.

The invocation owns an ordered, memory-only attachment list. The attachment manager edits a local copy. **Apply Attachments** updates that invocation only; **Cancel** leaves it unchanged. Opening or reloading a template and switching libraries clear attachments. Handoff between Quick Use and the tool window retains them. Reset Values and Refresh Context retain attachments; the attachment manager has its own explicit **Refresh Selected** action. A dialog cannot apply to a different invocation opened while it was active.

The list shows each captured item, source, capture time, byte size and a Remove action. Selecting an item displays its captured content. No source is read merely because it appears in the project or is named in a template.

## Ordering and text

Items retain addition order. A multi-file selection is sorted by full path before addition. Selecting an already attached source replaces it at the same position. Removing an item removes its entire block. Refresh retains position and source selection. No implicit reordering occurs.

Each item is a numbered Markdown section with its source provenance and a fenced literal block. The fence is longer than any backtick run in the captured text. Source text, including whitespace and `{{...}}`, is copied unchanged inside the block. Values are substituted once by StrictPromptRenderer; attachment content is never parsed as template syntax. Preview, Copy, Insert and rendered export use the same PromptInvocation render.

## Limits and failed capture

The limits are 16 items, 256 KiB of UTF-8 text per item, and 1 MiB of total captured text. Encoded source files also have a 1 MiB read limit before decoding. Formatting adds an explicit header to each item. A capture that exceeds a limit fails; there is no automatic truncation. A multi-file addition is atomic: any failure leaves the prior list unchanged and reports the affected source. Users can select fewer or smaller items.

Missing files, directories, binary files and unreadable text fail visibly. An empty text file is valid. An empty Git diff reports that the selected scope has no changes. Binary Git changes are unsupported and cause the diff capture to fail rather than omit their contents silently. Failed refresh retains the old captured item and reports the failure. Changes to source files do not change the stored payload. Provenance states that capture is frozen and may now differ from the source; refreshing is an explicit review step.

## Files

**Current File** means the source text editor selected when the attachment manager was opened, with its full path shown. **Selected Files…** uses the platform file chooser; it does not traverse directories. Only local regular files are supported; remote and virtual-only sources are rejected.

A loaded text document supplies editor-buffer text, including unsaved changes. The item says **editor buffer (unsaved)** or **editor buffer (saved)**. Closed files use their on-disk text and declared charset. Capture checks source stability while reading and reports a retry if it changes. Refresh resolves the same source URL and uses its current buffer or disk state. It never saves or edits the source file.

## Git capability boundary

File capture requires only IntelliJ Platform. Git diff capture uses an optional Git4Idea dependency and its configured local Git executable. Without that plugin, the manager explains that Git capture is unavailable; file capture still works. Other VCS providers are outside this implementation.

The Git dialog requires an explicit repository and scope even in a single-root project. Repositories are shown by full root path. Supported scopes are:

- **Staged: HEAD → index**. The captured HEAD commit is the explicit base.
- **Unstaged: index → working tree**. The index is the base; the repository's current HEAD commit is shown as context, not mislabelled as the diff base.

A repository without a HEAD commit is unsupported. Diffs cover tracked changes in the chosen repository. Untracked files and unsaved editor changes are excluded by definition and the dialog says so before capture; attach their buffer/file text separately. Submodule content and binary patches are unsupported. No repository is selected from an arbitrary active file when several roots exist.

The adapter runs only fixed, read-only local Git operations through Git4Idea after an explicit capture or refresh. It opens no terminal and accepts no template command, executable, arguments or network operation. External diff, text-conversion, clean/process filters and fsmonitor helpers are disabled. Git output must be UTF-8 text; output containing decoding replacement characters is rejected. Output is bounded; errors report the capture failure without logging source content. The capture records its HEAD and scope and fails if HEAD changes during capture. A later repository change does not refresh an existing attachment.

The optional descriptor follows the [JetBrains plugin dependency contract](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html). Git APIs are checked against the pinned 262 SDK and verified in the supported IDE matrix.

## Validation

Core tests must cover ordering, replacement/removal, limits, literal delimiters and single substitution. IDE tests must cover unsaved buffers, frozen source changes, removal and refresh, failed capture, Quick Use handoff, multi-root Git selection and distinct staged/unstaged payloads. Separate exploratory tests must inspect provenance and exact delivered output. Plugin verification and the complete unit/IDE suites must pass before merge.
