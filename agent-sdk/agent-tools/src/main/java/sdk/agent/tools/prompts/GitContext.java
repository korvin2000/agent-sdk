package sdk.agent.tools.prompts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import sdk.agent.prompt.PromptTemplate;

/// kon's `# Git Context`: branch, main branch, `git status --porcelain` and the last five commits,
/// taken when the prompt is built. Empty outside a repository or when git does not answer.
public record GitContext(String branch, String mainBranch, String status, String recentCommits) {

    public static final GitContext NONE = new GitContext("", "", "", "");

    /// kon's cap on the rendered snapshot.
    public static final int MAX_CHARS = 2000;

    private static final PromptTemplate CONTEXT = Templates.load("git-context.md");
    private static final PromptTemplate TRUNCATED = Templates.load("git-truncated.md");
    private static final Duration QUICK = Duration.ofSeconds(5);
    private static final Duration SLOW = Duration.ofSeconds(10);
    private static final String ORIGIN = "refs/remotes/origin/";

    public GitContext {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(mainBranch, "mainBranch");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(recentCommits, "recentCommits");
    }

    /// The nearest ancestor of `start` holding `.git` (a directory, or a file for worktrees).
    public static Optional<Path> root(Path start) {
        for (Path dir = start.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) return Optional.of(dir);
        }
        return Optional.empty();
    }

    /// Never throws: [#NONE] when `workspace` is not inside a repository.
    public static GitContext of(Path workspace) {
        Path cwd = workspace.toAbsolutePath().normalize();
        if (git(cwd, QUICK, "rev-parse", "--git-dir").isEmpty()) return NONE;

        String mainBranch = "main";
        String remoteHead = git(cwd, QUICK, "symbolic-ref", ORIGIN + "HEAD");
        if (remoteHead.startsWith(ORIGIN)) {
            mainBranch = remoteHead.substring(ORIGIN.length());
        } else if (git(cwd, QUICK, "branch", "-r").contains("origin/master")) {
            mainBranch = "master";
        }
        return new GitContext(git(cwd, QUICK, "branch", "--show-current"), mainBranch,
                              git(cwd, SLOW, "status", "--porcelain"), git(cwd, SLOW, "log", "--oneline", "-5"));
    }

    /// `git-context.md`, or `""` when there is nothing to report.
    public String render() {
        var parts = new ArrayList<String>();
        if (!branch.isEmpty()) parts.add("Current branch: " + branch);
        if (!mainBranch.isEmpty()) parts.add("Main branch (you will usually use this for PRs): " + mainBranch);
        if (!status.isEmpty()) parts.add("Status:\n" + status);
        if (!recentCommits.isEmpty()) parts.add("Recent commits:\n" + recentCommits);
        if (parts.isEmpty()) return "";

        String content = String.join("\n\n", parts);
        if (content.length() > MAX_CHARS) content = content.substring(0, MAX_CHARS) + "\n\n" + TRUNCATED.text();
        return CONTEXT.render(Map.of("status", content));
    }

    /// One `git` subprocess; `""` on a non-zero exit, a timeout, or no `git` on `PATH`.
    static String git(Path cwd, Duration timeout, String... args) {
        var command = new ArrayList<String>(args.length + 1);
        command.add("git");
        command.addAll(List.of(args));
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .directory(cwd.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            var output = new AtomicReference<byte[]>();
            Process started = process;
            Thread reader = Thread.ofVirtual().start(() -> {          // drains stdout so a full pipe cannot block the wait
                try {
                    output.set(started.getInputStream().readAllBytes());
                } catch (IOException _) {
                    // output stays null and the probe reports nothing
                }
            });
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) return "";
            reader.join(timeout.toMillis());
            byte[] bytes = output.get();
            return process.exitValue() == 0 && bytes != null ? new String(bytes, StandardCharsets.UTF_8).strip() : "";
        } catch (IOException _) {
            return "";
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }
}
