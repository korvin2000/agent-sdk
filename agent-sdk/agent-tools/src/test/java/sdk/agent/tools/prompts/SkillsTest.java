package sdk.agent.tools.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillsTest {

    @TempDir Path temp;
    private Path repo;
    private Path user;

    @BeforeEach void layout() throws IOException {
        repo = Files.createDirectories(temp.resolve("repo"));
        Files.createDirectory(repo.resolve(".git"));
        user = Files.createDirectories(temp.resolve("user-skills"));
    }

    private static Path skill(Path skillsDir, String name, String content) throws IOException {
        Path file = Files.createDirectories(skillsDir.resolve(name)).resolve(Skills.FILE_NAME);
        Files.writeString(file, content);
        return file;
    }

    @Test
    @DisplayName("frontmatter: quotes, inline comments, comment lines, and the body after it")
    void frontmatter() {
        var meta = Skills.frontmatter("""
                ---
                name: "deploy"
                description: 'Ship it # not a comment'
                register_cmd: true   # trailing comment
                # a comment line
                cmd_info: ship
                ---
                body""");
        assertEquals(Map.of("name", "deploy", "description", "Ship it # not a comment", "register_cmd", "true", "cmd_info", "ship"), meta);
        assertEquals("body", Skills.stripFrontmatter("---\nname: x\n---\n\nbody\n"));
        assertEquals(Map.of(), Skills.frontmatter("no frontmatter"));
        assertEquals("no frontmatter", Skills.stripFrontmatter("no frontmatter"));
    }

    @Test
    @DisplayName("project skills from the workspace up to the git root, then user skills, then built-ins; first name wins")
    void discoveryOrderAndPrecedence() throws IOException {
        Path ws = Files.createDirectories(repo.resolve("apps").resolve("web"));
        skill(ws.resolve(Skills.SKILLS_DIR), "alpha", "---\ndescription: closest alpha\n---\nA");
        skill(repo.resolve(Skills.SKILLS_DIR), "alpha", "---\ndescription: root alpha\n---\nA");
        skill(repo.resolve(Skills.SKILLS_DIR), "beta", "---\ndescription: root beta\n---\nB");
        skill(user, "gamma", "---\ndescription: user gamma\n---\nG");
        skill(temp.resolve(Skills.SKILLS_DIR), "outside", "---\ndescription: above the git root\n---\nO");

        Skills skills = Skills.load(ws, user);
        assertEquals(List.of("alpha", "beta", "gamma", "init", "review"), skills.skills().stream().map(Skill::name).toList());
        assertEquals("closest alpha", skills.find("alpha").orElseThrow().description());
        assertTrue(skills.find("outside").isEmpty(), "discovery stops at the git root");
        assertEquals(1, skills.warnings().size(), skills.warnings().toString());
        assertTrue(skills.warnings().getFirst().contains("name collision: \"alpha\""), skills.warnings().toString());
    }

    @Test
    @DisplayName("validation warns; only a missing description drops the skill")
    void validation() throws IOException {
        skill(repo.resolve(Skills.SKILLS_DIR), "Bad_Name", "---\ndescription: d\ncmd_info: " + "x".repeat(33) + "\n---\nB");
        skill(repo.resolve(Skills.SKILLS_DIR), "nodesc", "---\nname: nodesc\n---\nno description");
        skill(repo.resolve(Skills.SKILLS_DIR), "renamed", "---\nname: other\ndescription: d\n---\nR");

        Skills skills = Skills.load(repo, user);
        assertTrue(skills.find("Bad_Name").isPresent(), "a bad name is a warning, not a rejection");
        assertTrue(skills.find("nodesc").isEmpty());
        assertTrue(skills.find("other").isPresent(), "the frontmatter name wins over the directory name");
        String all = String.join("\n", skills.warnings());
        assertTrue(all.contains("name must be lowercase a-z, 0-9, hyphens only"), all);
        assertTrue(all.contains("cmd_info exceeds 32 characters"), all);
        assertTrue(all.contains("description is required"), all);
        assertTrue(all.contains("name \"other\" does not match directory \"renamed\""), all);
    }

    @Test
    @DisplayName("render lists prompt-visible skills only, XML-escaped")
    void render() throws IOException {
        Path alpha = skill(repo.resolve(Skills.SKILLS_DIR), "alpha", "---\ndescription: Alpha & <co>\n---\nA");
        skill(repo.resolve(Skills.SKILLS_DIR), "hidden", "---\ndescription: command only\nregister_cmd: only\n---\nH");

        String rendered = Skills.load(repo, user).render();
        assertTrue(rendered.startsWith("# Skills\n\nThe following skills provide specialized instructions for specific tasks.\n"), rendered);
        assertTrue(rendered.endsWith("<available_skills>\n<skill>\n<name>alpha</name>\n<description>Alpha &amp; &lt;co&gt;</description>\n"
                + "<path>" + Xml.escape(alpha.toString()) + "</path>\n</skill>\n</available_skills>"), rendered);
        assertFalse(rendered.contains("hidden"));
        assertFalse(rendered.contains("<name>init</name>"), "built-ins are commands only");
        assertEquals("", new Skills(List.of(), List.of()).render());
    }

    @Test
    @DisplayName("slash commands expand registered skills: $ARGUMENTS substituted, or the arguments appended")
    void commands() throws IOException {
        Path beta = skill(repo.resolve(Skills.SKILLS_DIR), "beta", "---\ndescription: d\nregister_cmd: true\n---\nDo: $ARGUMENTS\nend");
        skill(repo.resolve(Skills.SKILLS_DIR), "plain", "---\ndescription: d\nregister_cmd: yes\n---\nJust do it.");
        skill(repo.resolve(Skills.SKILLS_DIR), "silent", "---\ndescription: d\n---\nS");
        Skills skills = Skills.load(repo, user);

        assertEquals("<skill name=\"beta\" location=\"" + Xml.escape(beta.toString()) + "\">\nReferences are relative to "
                + beta.getParent() + ".\n\nDo: fix tests\nend\n</skill>", skills.command("/beta  fix tests ").orElseThrow());
        assertTrue(skills.command("/plain now").orElseThrow().endsWith("\n\nJust do it.\n\nnow\n</skill>"));
        assertTrue(skills.command("/plain").orElseThrow().endsWith("\n\nJust do it.\n</skill>"));
        assertTrue(skills.command("/silent go").isEmpty(), "not registered as a command");
        assertTrue(skills.command("/nope").isEmpty());
        assertTrue(skills.command("beta").isEmpty(), "not a command");
    }

    @Test
    @DisplayName("the built-in init and review skills ship as commands only")
    void builtIns() {
        Skills builtin = Skills.builtin();
        assertEquals(List.of("init", "review"), builtin.skills().stream().map(Skill::name).toList());
        assertEquals(List.of(), builtin.warnings());
        for (Skill s : builtin.skills()) {
            assertTrue(s.registerCmd() && !s.includeInPrompt(), s.name());
            assertTrue(s.location().startsWith("classpath:/sdk/agent/tools/prompts/skills/"), s.location());
            assertFalse(s.body().startsWith("---"), "frontmatter stripped");
        }
        assertTrue(builtin.command("/init focus on tests").orElseThrow().contains("focus on tests"));
        assertTrue(builtin.command("/review").orElseThrow().contains("Review the requested code changes"));
    }
}
