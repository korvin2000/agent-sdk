package sdk.agent.tools.shell;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import sdk.agent.tools.support.ToolException;

/// Finds the shell `bash` runs commands through by probing the machine ([#platform]); a host that
/// already knows supplies a `ShellSpec` to `ToolEnvironment.Builder.shell` instead. The PATH scan
/// is pure Java — no `where`/`which` subprocess, no 5 s timeout, no locale issues.
///
/// Windows preference order, all with `bash.exe` at the end: `%ProgramFiles%/Git/bin`,
/// `%ProgramFiles(x86)%/Git/bin`, `%LOCALAPPDATA%/Programs/Git/bin` (the per-user Git install,
/// missing from pi), `%ProgramFiles%/Git/usr/bin`, then PATH. The configured shell comes first of
/// all, but a host that supplied one never reaches this class. Elsewhere: `/bin/bash`, PATH, `sh`.
///
/// **Rejecting `System32` is not optional.** On stock Windows 10/11 `C:\Windows\System32\bash.exe`
/// is the WSL launcher. It *is* a bash, so an existence check passes, but its filesystem namespace
/// is `/mnt/c/...`, not `C:\...` — every `cwd`, every path passed and every path printed is
/// silently in the wrong namespace. It is the nastiest trap in the whole port because it fails
/// silently. `Sysnative` is the same directory seen from a 32-bit process.
///
/// The package-private constructor is the test seam: a fake environment and a fake candidate
/// list, so discovery — including the `System32` rejection — is exercised on any machine.
public final class ShellDiscovery {

    /// The candidate list is rendered from the same preference order the search uses, so the
    /// message can never drift from it.
    private static final String NO_SHELL_FOUND = """
            No bash shell found. Options:
              1. Install Git for Windows: https://git-scm.com/download/win
              2. Add your bash to PATH (Cygwin, MSYS2, etc.)
              3. Supply a ShellSpec to ToolEnvironment.Builder.shell(...)

            Searched Git Bash in:
            %s""";

    private static final List<String> DEFAULT_PATHEXT = List.of(".COM", ".EXE", ".BAT", ".CMD");

    private final boolean windows;
    private final UnaryOperator<String> environment;
    private final Predicate<Path> executable;

    private ShellDiscovery() {
        this(CommandRunner.IS_WINDOWS, System::getenv, ShellDiscovery::isExecutableFile);
    }

    ShellDiscovery(boolean windows, UnaryOperator<String> environment, Predicate<Path> executable) {
        this.windows = windows;
        this.environment = Objects.requireNonNull(environment, "environment");
        this.executable = Objects.requireNonNull(executable, "executable");
    }

    public static ShellDiscovery platform() { return new ShellDiscovery(); }

    /// @throws ToolException with a user-facing explanation when no shell can be found
    public ShellSpec discover() {
        var searched = new ArrayList<String>();
        ShellSpec found = windows ? discoverWindows(searched) : discoverPosix(searched);
        if (found != null) return found;
        throw new ToolException(NO_SHELL_FOUND.formatted(
                searched.stream().map(s -> "  " + s).collect(Collectors.joining("\n"))));
    }

    private ShellSpec discoverWindows(List<String> searched) {
        for (Path candidate : gitBashLocations()) {
            searched.add(candidate.toString());
            if (executable.test(candidate)) return ShellSpec.bash(candidate);
        }
        searched.add("PATH (excluding %SystemRoot%\\System32 and Sysnative)");
        return firstOnPath("bash");
    }

    private ShellSpec discoverPosix(List<String> searched) {
        ShellSpec bash = firstOf(searched, List.of(Path.of("/bin/bash"), Path.of("/usr/bin/bash")));
        if (bash != null) return bash;
        searched.add("PATH");
        bash = firstOnPath("bash");
        if (bash != null) return bash;
        ShellSpec sh = firstOf(searched, List.of(Path.of("/bin/sh"), Path.of("/usr/bin/sh")));
        return sh != null ? sh : firstOnPath("sh");
    }

    private ShellSpec firstOf(List<String> searched, List<Path> candidates) {
        for (Path candidate : candidates) {
            searched.add(candidate.toString());
            if (executable.test(candidate)) return ShellSpec.bash(candidate);
        }
        return null;
    }

    private ShellSpec firstOnPath(String name) {
        for (Path candidate : onPath(name)) {
            if (executable.test(candidate)) return ShellSpec.bash(candidate);
        }
        return null;
    }

    private List<Path> gitBashLocations() {
        var out = new ArrayList<Path>(4);
        under(out, environment.apply("ProgramFiles"), "Git", "bin", "bash.exe");
        under(out, environment.apply("ProgramFiles(x86)"), "Git", "bin", "bash.exe");
        under(out, environment.apply("LOCALAPPDATA"), "Programs", "Git", "bin", "bash.exe");
        under(out, environment.apply("ProgramFiles"), "Git", "usr", "bin", "bash.exe");
        return out;
    }

    private static void under(List<Path> out, String root, String... more) {
        if (root == null || root.isBlank()) return;
        try { out.add(Path.of(root, more)); } catch (InvalidPathException _) { /* unusable entry */ }
    }

    private List<Path> onPath(String name) {
        String path = environment.apply("PATH");
        if (path == null || path.isBlank()) return List.of();
        var out = new ArrayList<Path>();
        for (String entry : path.split(windows ? ";" : ":", -1)) {
            if (entry.isBlank()) continue;
            Path dir;
            try { dir = Path.of(entry.trim()); } catch (InvalidPathException _) { continue; }
            if (windows && isWindowsSystemDirectory(dir)) continue;
            if (windows) {
                for (String ext : pathExtensions()) out.add(dir.resolve(name + ext));
            } else {
                out.add(dir.resolve(name));
            }
        }
        return out;
    }

    private List<String> pathExtensions() {
        String pathext = environment.apply("PATHEXT");
        if (pathext == null || pathext.isBlank()) return DEFAULT_PATHEXT;
        var out = new ArrayList<String>();
        for (String ext : pathext.split(";", -1)) {
            if (!ext.isBlank()) out.add(ext.trim().startsWith(".") ? ext.trim() : "." + ext.trim());
        }
        return out.isEmpty() ? DEFAULT_PATHEXT : out;
    }

    private static boolean isWindowsSystemDirectory(Path dir) {
        Path name = dir.getFileName();
        if (name == null) return false;
        String last = name.toString();
        return last.equalsIgnoreCase("System32") || last.equalsIgnoreCase("Sysnative");
    }

    private static boolean isExecutableFile(Path p) {
        return Files.isRegularFile(p) && Files.isExecutable(p);
    }
}
