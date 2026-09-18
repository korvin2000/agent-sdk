package sdk.agent.tools.prompts;

import java.util.Map;
import java.util.Objects;

import sdk.agent.prompt.PromptTemplate;

/// One skill: kon's `Skill` plus its `SKILL.md` body, so a slash command expands without re-reading.
///
/// @param location        a filesystem path, or `classpath:/…` for a built-in
/// @param body            the instructions, frontmatter removed
/// @param registerCmd     whether `/name` is a slash command
/// @param includeInPrompt whether `# Skills` lists it (`register_cmd: only` hides it)
public record Skill(String name, String description, String location, String body,
                    boolean registerCmd, String cmdInfo, boolean includeInPrompt) {

    /// Placeholder in a command body for whatever followed `/name`.
    public static final String ARGUMENTS = "$ARGUMENTS";

    private static final PromptTemplate COMMAND = Templates.load("skill-command.md");

    public Skill {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(cmdInfo, "cmdInfo");
    }

    /// kon's `render_skill_prompt`: `skill-command.md` with `$ARGUMENTS` replaced, or the arguments appended.
    public String render(String arguments) {
        String query = arguments.strip();
        String rendered = body.contains(ARGUMENTS)
                ? body.replace(ARGUMENTS, query).strip()
                : query.isEmpty() ? body.strip() : body.strip() + "\n\n" + query;
        return COMMAND.render(Map.of(
                "name", Xml.escape(name),
                "location", Xml.escape(location),
                "directory", directory(),
                "body", rendered));
    }

    /// The directory holding the `SKILL.md`; relative references in the body resolve against it.
    public String directory() {
        int cut = Math.max(location.lastIndexOf('/'), location.lastIndexOf('\\'));
        return cut < 0 ? location : location.substring(0, cut);
    }
}
