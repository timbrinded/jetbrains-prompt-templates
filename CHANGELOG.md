# Changelog

## 0.3.1 — 2026-10-05

Safer deletion, recoverable settings and steadier Quick Use.

- Deleting a folder no longer follows Windows directory junctions, and a template folder that also holds other files or folders is not deleted as a template.
- Template deletion checks the template's identity, so an out-of-date library view cannot delete a different template.
- Validated the library path in Settings, accepted quoted paths and kept a bad stored path from breaking the plugin.
- Kept an open Quick Use form when the library changes on disk.
- Kept metadata fields from newer plugin versions and existing file permissions when saving, and left unreadable folder order files unchanged.
- Changes wait at most 10 seconds for another IDE that holds the library lock; reading the library waits without clearing the view.
- Opened library tree menus from anywhere on a row or with the context-menu key, and left Shift+F10 to Run.
- Showed placeholder highlights as soon as the author view opens, warned about escaped placeholders that name a variable, and protected unsaved drafts when a project closes.
- Rejected Windows device names as folder names and shortened directory names derived from long template names.
- Verified compatibility weekly against the newest release and EAP builds of IntelliJ IDEA, RustRover and WebStorm.

## 0.3.0 — 2026-09-05

- Added keyboard Quick Use with ranked search, favourites, recents and shared tool-window invocation.
- Kept preview and delivery on an inspectable context snapshot, with explicit refresh, reload and insertion-target selection.
- Added recoverable two-file saves, revision-checked overwrite decisions and protection for changed author drafts.
- Improved narrow and scaled layouts, referenced-only forms and multiline validation focus.
- Added Duplicate Template and Create Template from Selection with explicit literal or placeholder handling.
- Exposed authored defaults, input presentation, field ordering and reset controls.
- Added Insert Variable and Extract as Variable with coordinated Markdown/schema Undo and Redo.
- Added explicit file-buffer and staged/unstaged Git-diff attachments with provenance, size limits and frozen output.
- Added independent rendered Markdown scratch files and three optional, editable worked examples.
- Kept input controls synchronized with shared invocation values in Quick Use and the tool window.

## 0.2.0 — 2026-09-04

- Added nested organiser folders for prompt templates.
- Added portable manual order, folder and template drag-and-drop, and keyboard move actions.
- Added focused root, folder and template context menus for common library actions.
- Added guarded recursive folder deletion and hierarchy-aware search and file watching.
- Remembered expanded folders and the selected template per project.
- Added a local IntelliJ IDEA Starter/Driver end-to-end check with commit signoff.

## 0.1.1 — 2026-08-07

- Clarified platform-wide product compatibility separately from the current RustRover and WebStorm verification matrix.

## 0.1.0 — 2026-08-07

- Added a native searchable prompt-template library.
- Added Markdown authoring with placeholder discovery, highlighting and typed variables.
- Added strict rendering, context providers, live preview, clipboard delivery and editor insertion.
- Added file-backed persistence, recovery diagnostics, import, source operations and plain/rendered export.
- Added responsive wide and narrow tool-window layouts.
- Added independent core unit and repository tests plus CI plugin builds.
