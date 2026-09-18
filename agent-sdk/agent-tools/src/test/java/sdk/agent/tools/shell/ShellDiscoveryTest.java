package sdk.agent.tools.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sdk.agent.tools.support.ToolException;

/// Discovery against a fake environment and a fake candidate list, so the order — and the
/// `System32` rejection in particular — is exercised on any machine, configured or not.
class ShellDiscoveryTest {

    private static final String PF = "C:\\Program Files";
    private static final String PF86 = "C:\\Program Files (x86)";
    private static final String LOCAL = "C:\\Users\\u\\AppData\\Local";

    private static ShellDiscovery discovery(boolean windows, Map<String, String> environment, String... present) {
        Set<Path> existing = Arrays.stream(present).map(Path::of).collect(Collectors.toUnmodifiableSet());
        return new ShellDiscovery(windows, environment::get, existing::contains);
    }

    private static ShellDiscovery windows(Map<String, String> environment, String... present) {
        return discovery(true, environment, present);
    }

    private static ShellDiscovery posix(Map<String, String> environment, String... present) {
        return discovery(false, environment, present);
    }

    // ---- Windows order -------------------------------------------------------------------------

    @Test void prefersProgramFilesGitBashOverEverythingElse() throws ToolException {
        ShellSpec spec = windows(Map.of("ProgramFiles", PF, "ProgramFiles(x86)", PF86, "LOCALAPPDATA", LOCAL,
                                        "PATH", "C:\\msys64\\usr\\bin", "PATHEXT", ".exe"),
                                 PF + "\\Git\\bin\\bash.exe",
                                 PF86 + "\\Git\\bin\\bash.exe",
                                 "C:\\msys64\\usr\\bin\\bash.exe").discover();
        assertEquals(Path.of(PF, "Git", "bin", "bash.exe"), spec.executable());
        assertEquals(List.of("-c"), spec.args());
    }

    @Test @DisplayName("the per-user Git install is found — the entry missing from pi")
    void findsThePerUserGitInstall() throws ToolException {
        ShellSpec spec = windows(Map.of("ProgramFiles", PF, "LOCALAPPDATA", LOCAL, "PATH", ""),
                                 LOCAL + "\\Programs\\Git\\bin\\bash.exe").discover();
        assertEquals(Path.of(LOCAL, "Programs", "Git", "bin", "bash.exe"), spec.executable());
    }

    @Test void fallsBackToTheGitUsrBinShell() throws ToolException {
        ShellSpec spec = windows(Map.of("ProgramFiles", PF, "PATH", ""),
                                 PF + "\\Git\\usr\\bin\\bash.exe").discover();
        assertEquals(Path.of(PF, "Git", "usr", "bin", "bash.exe"), spec.executable());
    }

    @Test void scansPathWhenNoGitInstallIsPresent() throws ToolException {
        ShellSpec spec = windows(Map.of("ProgramFiles", PF, "PATH", "C:\\cygwin64\\bin;C:\\other",
                                        "PATHEXT", ".exe"),
                                 "C:\\cygwin64\\bin\\bash.exe").discover();
        assertEquals(Path.of("C:\\cygwin64\\bin\\bash.exe"), spec.executable());
    }

    // ---- the trap ------------------------------------------------------------------------------

    @Test @DisplayName("System32 bash.exe — the WSL launcher — is rejected even though it exists")
    void rejectsTheWslLauncherInSystem32() {
        ShellDiscovery discovery = windows(Map.of("ProgramFiles", PF, "PATH", "C:\\Windows\\System32",
                                                  "PATHEXT", ".exe"),
                                           "C:\\Windows\\System32\\bash.exe");
        var thrown = assertThrows(ToolException.class, discovery::discover);
        assertTrue(thrown.getMessage().startsWith("No bash shell found. Options:"), thrown.getMessage());
    }

    @Test void rejectsSysnativeToo() {
        ShellDiscovery discovery = windows(Map.of("ProgramFiles", PF, "PATH", "C:\\Windows\\Sysnative",
                                                  "PATHEXT", ".exe"),
                                           "C:\\Windows\\Sysnative\\bash.exe");
        assertThrows(ToolException.class, discovery::discover);
    }

    @Test @DisplayName("a real bash later on PATH still wins after System32 is skipped")
    void skipsSystem32ButKeepsScanning() throws ToolException {
        ShellSpec spec = windows(Map.of("ProgramFiles", PF, "PATHEXT", ".exe",
                                        "PATH", "C:\\Windows\\System32;C:\\msys64\\usr\\bin"),
                                 "C:\\Windows\\System32\\bash.exe",
                                 "C:\\msys64\\usr\\bin\\bash.exe").discover();
        assertEquals(Path.of("C:\\msys64\\usr\\bin\\bash.exe"), spec.executable());
    }

    // ---- the message a host sees first -----------------------------------------------------------

    @Test void theNotFoundMessageListsEveryProbedLocation() {
        ShellDiscovery discovery = windows(Map.of("ProgramFiles", PF, "ProgramFiles(x86)", PF86,
                                                  "LOCALAPPDATA", LOCAL, "PATH", "C:\\bin", "PATHEXT", ".exe"));
        String message = assertThrows(ToolException.class, discovery::discover).getMessage();
        assertEquals("""
                No bash shell found. Options:
                  1. Install Git for Windows: https://git-scm.com/download/win
                  2. Add your bash to PATH (Cygwin, MSYS2, etc.)
                  3. Supply a ShellSpec to ToolEnvironment.Builder.shell(...)

                Searched Git Bash in:
                  %s
                  %s
                  %s
                  %s
                  PATH (excluding %%SystemRoot%%\\System32 and Sysnative)""".formatted(
                        Path.of(PF, "Git", "bin", "bash.exe"),
                        Path.of(PF86, "Git", "bin", "bash.exe"),
                        Path.of(LOCAL, "Programs", "Git", "bin", "bash.exe"),
                        Path.of(PF, "Git", "usr", "bin", "bash.exe")),
                message);
    }

    // ---- POSIX ------------------------------------------------------------------------------------

    @Test void posixPrefersBinBash() throws ToolException {
        ShellSpec spec = posix(Map.of("PATH", "/usr/local/bin"), "/bin/bash", "/usr/local/bin/bash").discover();
        assertEquals(Path.of("/bin/bash"), spec.executable());
    }

    @Test void posixFallsBackToPathThenSh() throws ToolException {
        assertEquals(Path.of("/usr/local/bin/bash"),
                posix(Map.of("PATH", "/usr/local/bin"), "/usr/local/bin/bash").discover().executable());
        assertEquals(Path.of("/bin/sh"),
                posix(Map.of("PATH", "/usr/local/bin"), "/bin/sh").discover().executable());
    }

    @Test void posixReportsWhenNothingIsFound() {
        String message = assertThrows(ToolException.class,
                posix(Map.of("PATH", "/usr/bin"))::discover).getMessage();
        assertTrue(message.contains(Path.of("/bin/bash").toString()), message);
        assertTrue(message.contains(Path.of("/bin/sh").toString()), message);
    }
}
