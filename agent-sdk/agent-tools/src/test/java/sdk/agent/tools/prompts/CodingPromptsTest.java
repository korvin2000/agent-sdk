package sdk.agent.tools.prompts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sdk.agent.prompt.PromptContext;
import sdk.agent.prompt.SectionSpec;
import sdk.agent.prompt.SystemPromptBuilder;
import sdk.agent.testkit.FakeTool;
import sdk.agent.tool.Tool;
import sdk.agent.tools.ToolEnvironment;

class CodingPromptsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-03T14:05:06Z"), ZoneOffset.UTC);

    @TempDir Path temp;

    /// A bare `.git` directory stops AGENTS.md and skill discovery here, and `git rev-parse` rejects it.
    private Path workspace() throws IOException {
        Path ws = Files.createDirectories(temp.resolve("repo"));
        Files.createDirectory(ws.resolve(".git"));
        return ws;
    }

    private ToolEnvironment env(Path ws) { return ToolEnvironment.builder(ws).clock(CLOCK).build(); }

    private static String build(CodingPrompts prompts, Tool<?>... tools) {
        return SystemPromptBuilder.build(new PromptContext(List.of(tools)), prompts.sections(), Optional.empty());
    }

    @Test
    @DisplayName("base text, tool usage and env, in that order, with the fixed clock's date")
    void baseToolUsageAndEnv() throws IOException {
        Path ws = workspace();
        var read = FakeTool.named("read").guidelines("Use read to examine files instead of cat or sed.");
        var bash = FakeTool.named("bash").guidelines("Quote paths that may contain spaces.", "Use read to examine files instead of cat or sed.");

        String prompt = build(new CodingPrompts(env(ws)), read, bash);
        assertTrue(prompt.startsWith(CodingPrompts.DEFAULT_BASE.text()
                + "\n\n# Tool usage\n\n- Use read to examine files instead of cat or sed.\n- Quote paths that may contain spaces.\n\n"), prompt);
        assertTrue(prompt.endsWith("\n\n# Env\n\nCurrent date and time: Tuesday, February 03, 2026 at 02:05 PM UTC\n"
                + "Current working directory: " + ws.toAbsolutePath().normalize()), prompt);
    }

    @Test
    @DisplayName("project context and skills slot between tool usage and env")
    void projectContextAndSkills() throws IOException {
        Path ws = workspace();
        Files.writeString(ws.resolve("AGENTS.md"), "House rules");
        Files.writeString(Files.createDirectories(ws.resolve(Skills.SKILLS_DIR).resolve("deploy")).resolve(Skills.FILE_NAME),
                "---\ndescription: Ship it\n---\nsteps");

        String prompt = build(new CodingPrompts(env(ws)), FakeTool.named("read").guidelines("g"));
        int usage = prompt.indexOf("\n\n# Tool usage\n\n- g\n\n");
        int project = prompt.indexOf("# Project Context\n");
        int skills = prompt.indexOf("# Skills\n");
        int env = prompt.indexOf("# Env\n");
        assertTrue(usage > 0 && project > usage && skills > project && env > skills, prompt);
        assertTrue(prompt.contains("<file path=\"" + Xml.escape(ws.resolve("AGENTS.md").toString()) + "\">\nHouse rules\n</file>"), prompt);
        assertTrue(prompt.contains("<name>deploy</name>\n<description>Ship it</description>"), prompt);
        assertFalse(prompt.contains("# Git Context"), "a bare .git directory is not a repository");
    }

    @Test
    @DisplayName("the base text is replaceable and the git snapshot can be switched off")
    void baseTextAndGitSwitch() throws IOException {
        var prompts = new CodingPrompts(env(workspace()), "You are Grendel.", false);
        assertTrue(build(prompts).startsWith("You are Grendel.\n\n# Env\n\n"));
        SectionSpec git = prompts.sections().stream().filter(s -> s.id().equals("git-context")).findFirst().orElseThrow();
        assertFalse(git.when().test(new PromptContext(List.of())));
    }

    @Test
    @DisplayName("six sections with unique ids at orders 0-50 that validate")
    void sectionsValidate() throws IOException {
        var sections = new CodingPrompts(env(workspace())).sections();
        assertEquals(List.of("base", "tool-usage", "project-context", "skills", "git-context", "env"),
                     sections.stream().map(SectionSpec::id).toList());
        assertEquals(List.of(0, 10, 20, 30, 40, 50), sections.stream().map(SectionSpec::order).toList());
        assertDoesNotThrow(() -> SystemPromptBuilder.validate(Map.of(CodingPrompts.CONTRIBUTOR_ID, sections)));
    }

    @Test
    @DisplayName("tool usage drops duplicates and disappears without guidelines")
    void toolUsage() {
        assertEquals("", CodingPrompts.toolUsage(new PromptContext(List.of(FakeTool.named("mute")))));
        assertEquals("# Tool usage\n\n- a\n- b", CodingPrompts.toolUsage(new PromptContext(List.of(
                FakeTool.named("x").guidelines("a", "b"), FakeTool.named("y").guidelines("b", "a")))));
    }
}
