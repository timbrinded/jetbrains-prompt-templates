# Privacy

Prompt Templates operates locally.

- It sends no network requests and includes no telemetry.
- Template bodies and typed schemas are stored only in the configured local library.
- Values entered into generated forms are session-only and are not written into template metadata.
- Prompt content and entered values are not logged.
- Clipboard reads occur only when a selected template references `{{clipboard}}`.
- Clipboard writes, editor insertion, imports and exports require explicit user actions.

The plugin does not invoke an LLM or integrate through private JetBrains AI Assistant, Junie or third-party agent APIs.
