# Privacy

Prompt Templates operates locally.

- It sends no network requests and includes no telemetry.
- Template bodies and typed schemas are stored only in the configured local library.
- Values entered into generated forms are session-only and are not written into template metadata.
- Prompt content and entered values are not logged.
- Clipboard reads occur only when a selected template references `{{clipboard}}`.
- Clipboard writes, editor insertion, imports and exports require explicit user actions.
- **Open Rendered Prompt as Scratch Markdown** is an explicit local export. The IDE retains the rendered text in its scratch storage, outside the template library, until the user removes it. Scratch output can include entered values and captured context; it is separate from memory-only invocation state.
- File and Git diff attachments are captured only after explicit selection and remain in the current invocation in memory. They are not written into template metadata or settings. Output/export can include them only through the inspected render.
- Git capture runs fixed local read operations through the optional Git integration. It opens no terminal, executes no template commands and disables external diff, text-conversion, clean/process filters and fsmonitor helpers.

The plugin does not invoke an LLM or integrate through private JetBrains AI Assistant, Junie or third-party agent APIs.
