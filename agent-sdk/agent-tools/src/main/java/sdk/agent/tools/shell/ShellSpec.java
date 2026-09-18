package sdk.agent.tools.shell;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// The shell `bash` runs commands through. `args` is the invocation prefix (`-c`); the command is
/// appended as one argument.
public record ShellSpec(Path executable, List<String> args) {

    public ShellSpec {
        Objects.requireNonNull(executable, "executable");
        args = List.copyOf(args);
    }

    public static ShellSpec bash(Path executable) { return new ShellSpec(executable, List.of("-c")); }
}
