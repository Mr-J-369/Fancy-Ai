# Character output formatting

Character prose uses the shared `MessageMarkdown` renderer in
`app/src/main/java/com/mrj/fancyai/ui/kit/MessageContent.kt`.
The same formatting applies to chat, social posts and comments, group conversations,
games, phone transcripts, character descriptions, memory summaries, and vision output.
Labels, names, controls, and editable text fields retain their normal UI formatting.
This document describes rendering; it is not automatically added to model instructions.

## Inline formatting

| Source | Appearance |
| --- | --- |
| `*She smiles and steps closer.*` | Italic roleplay action in the theme's gold `Accent` color. |
| `_soft emphasis_` | The same gold italic emphasis. |
| `**strong emphasis**` or `__strong emphasis__` | Bold text. |
| `***strong action***` | Bold, gold italic text. |
| `*She gives a **warm** smile.*` | Gold italic action with nested bold emphasis. |
| `~~crossed out~~` | Strikethrough. |
| `` `literal *text*` `` | Monospace inline code; asterisks are literal. |
| `[label](https://example.com)` | Clickable link. |
| `\*literal asterisks\*` | Visible asterisks without roleplay styling. |

Normal dialogue retains the surrounding text color. Quotation marks do not activate
roleplay styling. Use paired Markdown delimiters; an unfinished streamed delimiter
may remain literal until its closing delimiter arrives.

## Block formatting

- Separate paragraphs with a blank line.
- Use `#` through `######` followed by a space for headings.
- Start a line with `> ` for a quotation.
- Start list items with `- `, `* `, or a number followed by `. `.
- Use `- [ ]` and `- [x]` for task-list items.
- Use three backticks on separate lines for a fenced code block.
- Use a pipe-separated header, separator row, and data rows for a table.
- Use `---` on its own, separated by blank lines, for a horizontal rule.

Code blocks preserve literal syntax. The existing Markdown parser owns nesting,
escaping, links, lists, and tables; mini-apps must not implement their own parsers.

## Theme and image output

Gold emphasis uses `Accent` from `ui/theme/Color.kt`, not a separate hardcoded color.
Typography follows the app theme and system font scaling.

`<scene_prompt>...</scene_prompt>` is an image-generation marker, not Markdown
formatting. Existing image-output handling extracts it before displaying the prose.
Markdown styling does not alter saved messages, generation prompts, or image metadata.
