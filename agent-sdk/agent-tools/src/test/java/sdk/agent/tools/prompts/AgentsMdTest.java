package sdk.agent.tools.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentsMdTest {

    @TempDir Path temp;

    @Test
    @DisplayName("files are collected from the git root down to the workspace, outermost first; AGENTS.md beats CLAUDE.md")
    void collectsFromTheGitRootDownToTheWorkspace() throws IOException {
        Files.writeString(temp.resolve("AGENTS.md"), "above the repository: never loaded");
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Files.createDirectory(repo.resolve(".git"));
        Files.writeString(repo.resolve("AGENTS.md"), "root rules");
        Path mid = Files.createDirectories(repo.resolve("mid"));
        Files.writeString(mid.resolve("CLAUDE.md"), "mid rules");
        Path ws = Files.createDirectories(mid.resolve("ws"));
        Files.writeString(ws.resolve("AGENTS.md"), "ws rules");
        Files.writeString(ws.resolve("CLAUDE.md"), "shadowed");

        var loaded = AgentsMd.load(ws);
        assertEquals(List.of(repo.resolve("AGENTS.md"), mid.resolve("CLAUDE.md"), ws.resolve("AGENTS.md")),
                     loaded.files().stream().map(AgentsMd.ContextFile::path).toList());
        assertEquals(List.of("root rules", "mid rules", "ws rules"),
                     loaded.files().stream().map(AgentsMd.ContextFile::content).toList());
    }

    @Test
    @DisplayName("no file at all renders nothing")
    void nothingToSay() throws IOException {
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Files.createDirectory(repo.resolve(".git"));
        assertEquals(List.of(), AgentsMd.load(repo).files());
        assertEquals("", AgentsMd.load(repo).render());
    }

    @Test
    @DisplayName("render is kon's project-context block, XML-escaped")
    void rendersTheProjectContextBlock() {
        Path path = Path.of("w", "AGENTS.md");
        var md = new AgentsMd(List.of(new AgentsMd.ContextFile(path, "Use <b> & \"quotes\"\n")));
        assertEquals("""
                # Project Context

                Project guidelines for coding agents.

                <project_guidelines>
                <file path="%s">
                Use &lt;b&gt; &amp; &quot;quotes&quot;

                </file>
                </project_guidelines>""".formatted(path), md.render());
    }
}
