package sdk.agent.tools.prompts;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import sdk.agent.prompt.PromptContext;
import sdk.agent.prompt.PromptContributor;
import sdk.agent.prompt.PromptTemplate;
import sdk.agent.prompt.SectionSpec;
import sdk.agent.tools.ToolEnvironment;

/// kon's system prompt (`loop.py: build_system_prompt`): a short base text, then only what the
/// workspace adds. Six sections at orders 0–50 so another extension can slot its own between them.
/// Rendered when the prompt is built, once per run, so nothing is cached.
public final class CodingPrompts implements PromptContributor {

    public static final String CONTRIBUTOR_ID = "sdk.agent.tools";

    /// `system-prompt.md`: kon's default, minus the agent's name and kon's own session-log bullet.
    public static final PromptTemplate DEFAULT_BASE = Templates.load("system-prompt.md");

    /// kon's `%A, %B %d, %Y at %I:%M %p`; the zone follows separately.
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy 'at' hh:mm a", Locale.ENGLISH);

    private static final PromptTemplate TOOL_USAGE = Templates.load("tool-usage.md");
    private static final PromptTemplate ENV = Templates.load("env.md");

    private final ToolEnvironment env;
    private final String base;
    private final boolean gitContext;

    public CodingPrompts(ToolEnvironment env) { this(env, DEFAULT_BASE.text(), true); }

    /// @param base       the leading text; keep it short and put project specifics in `AGENTS.md`
    /// @param gitContext kon's `git_context` switch
    public CodingPrompts(ToolEnvironment env, String base, boolean gitContext) {
        this.env = Objects.requireNonNull(env, "env");
        this.base = Objects.requireNonNull(base, "base");
        this.gitContext = gitContext;
    }

    @Override public List<SectionSpec> sections() {
        return List.of(
                SectionSpec.always("base", 0, base),
                new SectionSpec("tool-usage", 10, _ -> true, CodingPrompts::toolUsage),
                new SectionSpec("project-context", 20, _ -> true, _ -> AgentsMd.load(env.workspace()).render()),
                new SectionSpec("skills", 30, _ -> true, _ -> Skills.load(env.workspace()).render()),
                new SectionSpec("git-context", 40, _ -> gitContext, _ -> GitContext.of(env.workspace()).render()),
                new SectionSpec("env", 50, _ -> true, _ -> environment()));
    }

    /// `tool-usage.md`: every tool's guidelines in registration order, duplicates dropped; `""` without any.
    static String toolUsage(PromptContext ctx) {
        List<String> guidelines = ctx.tools().stream().flatMap(t -> t.promptGuidelines().stream()).distinct().toList();
        return guidelines.isEmpty() ? "" : TOOL_USAGE.render(Map.of("guidelines", "- " + String.join("\n- ", guidelines)));
    }

    /// `env.md`: the clock's date and time, and the workspace root.
    String environment() {
        ZonedDateTime now = ZonedDateTime.now(env.clock());
        return ENV.render(Map.of(
                "dateTime", DATE_TIME.format(now) + " " + zoneName(now.getZone()),
                "cwd", env.workspace().toString()));
    }

    private static String zoneName(ZoneId zone) {
        return zone.normalized().equals(ZoneOffset.UTC) ? "UTC" : zone.getId();
    }
}
