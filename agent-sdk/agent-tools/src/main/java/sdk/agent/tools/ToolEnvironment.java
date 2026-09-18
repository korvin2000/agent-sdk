package sdk.agent.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/// Immutable workspace and process configuration shared by this tool pack.
public final class ToolEnvironment {

    private static final List<String> DEFAULT_SHELL = List.of("bash", "-c");
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(60);

    private final Path workspace;
    private final List<String> shell;
    private final Duration commandTimeout;
    final FileAccess files;

    public ToolEnvironment(Path workspace) {
        this(workspace, DEFAULT_SHELL, DEFAULT_COMMAND_TIMEOUT);
    }

    public ToolEnvironment(Path workspace, List<String> shell, Duration commandTimeout) {
        this.workspace = canonicalWorkspace(workspace);
        this.shell = copyShell(shell);
        this.commandTimeout = requirePositive(commandTimeout);
        files = new FileAccess(this.workspace);
    }

    public Path workspace() { return workspace; }

    public List<String> shell() { return shell; }

    public Duration commandTimeout() { return commandTimeout; }

    private static Path canonicalWorkspace(Path workspace) {
        Objects.requireNonNull(workspace, "workspace");
        try {
            Path canonical = workspace.toRealPath();
            if (!Files.isDirectory(canonical)) throw new IllegalArgumentException("workspace is not a directory: " + workspace);
            return canonical;
        } catch (IOException e) {
            throw new IllegalArgumentException("workspace must be an existing directory: " + workspace, e);
        }
    }

    private static List<String> copyShell(List<String> shell) {
        Objects.requireNonNull(shell, "shell");
        List<String> copy = List.copyOf(shell);
        if (copy.isEmpty() || copy.stream().anyMatch(s -> s == null || s.isBlank())) {
            throw new IllegalArgumentException("shell must be a nonempty command prefix");
        }
        return copy;
    }

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "commandTimeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("commandTimeout must be positive");
        return timeout;
    }
}
