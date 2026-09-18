# Skills

The following skills provide specialized instructions for specific tasks.
Use the read tool to load a skill's file when the task matches its description.
When a skill file references a relative path, resolve it against the skill's directory (the parent of its SKILL.md) and use that absolute path in tool calls, not a path relative to the current working directory.
If a skill is manually triggered via slash command, its full content is already included in the user message, so you don't need to read the skill file again.

<available_skills>
${skills}
</available_skills>
