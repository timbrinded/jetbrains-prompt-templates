# Worked examples

Open **Browse Examples…** from the empty state or **New | Browse Examples…**. Select an example and inspect its Markdown, mock inputs and expected output. **Add Example** creates an editable copy in the folder shown in the browser. Closing the browser adds nothing. New Template and Import remain available.

The three examples are original, agent-neutral templates bundled with the plugin. They need no account or download. Each package contains ordinary `prompt.md` and `prompt.meta.json` files. Adding a copy uses the normal repository create flow with a new UUID and an available name. Repeated additions do not replace existing copies, including copies you have edited. A concurrent conflict leaves a draft that you can rename and save.

| Example | What it demonstrates | Required real input |
| --- | --- | --- |
| [Review selected implementation](../core/src/main/resources/dev/timbrinded/prompttemplates/examples/review-selection/prompt.md) | Text and Multiline defaults, an Enum focus, and `ide.selection` | Select text in an editor. If unavailable, the Use view explains how to capture it; no mock code is substituted. |
| [Diagnose a supplied error](../core/src/main/resources/dev/timbrinded/prompttemplates/examples/diagnose-error/prompt.md) | A Text default, required Multiline evidence and optional checks | Enter the observed error. Nothing is collected automatically. |
| [Rewrite a technical explanation](../core/src/main/resources/dev/timbrinded/prompttemplates/examples/rewrite-explanation/prompt.md) | Enum audience/tone defaults and required Multiline text | Enter the explanation to rewrite. |

The browser is a mock walkthrough, separate from the active invocation. Its [input/context fixtures](../core/src/main/resources/dev/timbrinded/prompttemplates/examples/fixtures.json) contain no credentials or private project data. Their exact expected renders are:

- [Selection review output](../core/src/main/resources/dev/timbrinded/prompttemplates/examples/expected/review-selection.md)
- [Error diagnosis output](../core/src/main/resources/dev/timbrinded/prompttemplates/examples/expected/diagnose-error.md)
- [Explanation rewrite output](../core/src/main/resources/dev/timbrinded/prompttemplates/examples/expected/rewrite-explanation.md)

Codec, parser and renderer tests check every shipped package against those files. The fixtures are never installed as entered values or IDE context. After addition, normal required-value checks apply. Copy, Insert, rendered export and scratch export use the same validated invocation as any other template. Template Markdown export contains only the authored Markdown; it does not append JSON metadata.
