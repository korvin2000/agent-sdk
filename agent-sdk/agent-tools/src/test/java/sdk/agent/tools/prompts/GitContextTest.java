package sdk.agent.tools.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitContextTest {

    @TempDir Path dir;

    private static boolean gitAvailable() {
        return !GitContext.git(Path.of("."), Duration.ofSeconds(30), "--version").isEmpty();
    }

    /// Exit code of a git command in the temp dir, for setup that prints nothing on success.
    private int git(String... args) throws IOException {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        try {
            Process p = new ProcessBuilder(command).directory(dir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            return p.waitFor(30, TimeUnit.SECONDS) ? p.exitValue() : -1;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    @Test
    @DisplayName("outside a repository there is nothing to render")
    void outsideARepository() {
        Assumptions.assumeTrue(gitAvailable(), "git is not on PATH");
        Assumptions.assumeTrue(GitContext.root(dir).isEmpty(), "the temp dir is inside a repository");
        assertEquals(GitContext.NONE, GitContext.of(dir));
        assertEquals("", GitContext.NONE.render());
    }

    @Test
    @DisplayName("a repository yields branch, main branch, porcelain status and recent commits")
    void insideARepository() throws IOException {
        Assumptions.assumeTrue(gitAvailable(), "git is not on PATH");
        assertEquals(0, git("-c", "init.defaultBranch=main", "init", "-q"));
        Files.writeString(dir.resolve("a.txt"), "one\n");
        assertEquals(0, git("add", "a.txt"));
        assertEquals(0, git("-c", "user.email=t@example.invalid", "-c", "user.name=Test", "commit", "-q", "-m", "first"));
        Files.writeString(dir.resolve("b.txt"), "two\n");

        GitContext ctx = GitContext.of(dir);
        assertEquals("main", ctx.branch());
        assertEquals("main", ctx.mainBranch(), "no origin: kon's default");
        assertEquals("?? b.txt", ctx.status());
        assertTrue(ctx.recentCommits().endsWith(" first"), ctx.recentCommits());
        assertEquals(Optional.of(dir.toAbsolutePath().normalize()), GitContext.root(dir.resolve("deeper")));

        String rendered = ctx.render();
        assertTrue(rendered.startsWith("# Git Context\n\nThis is the git status at the start of the conversation."), rendered);
        assertTrue(rendered.contains("<git-status>\nCurrent branch: main\n\nMain branch (you will usually use this for PRs): main\n\n"
                + "Status:\n?? b.txt\n\nRecent commits:\n"), rendered);
        assertTrue(rendered.endsWith(" first\n</git-status>"), rendered);
    }

    @Test
    @DisplayName("the rendered snapshot is capped at 2000 chars with kon's notice")
    void renderIsCapped() {
        String rendered = new GitContext("main", "main", "M ".repeat(1500), "").render();
        String notice = "\n\n... (truncated because it exceeds 2k characters. If you need more information, run \"git status\" using bash)\n</git-status>";
        assertTrue(rendered.endsWith(notice), rendered);
        int start = rendered.indexOf("<git-status>\n") + "<git-status>\n".length();
        assertEquals(GitContext.MAX_CHARS, rendered.indexOf(notice) - start, "exactly the cap survives before the notice");
        assertEquals("", new GitContext("", "", "", "").render());
    }
}
