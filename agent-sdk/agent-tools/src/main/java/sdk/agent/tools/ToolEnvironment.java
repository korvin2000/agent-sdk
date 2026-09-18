package sdk.agent.tools;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import sdk.agent.tools.fs.FileVersions;
import sdk.agent.tools.shell.ShellDiscovery;
import sdk.agent.tools.shell.ShellSpec;
import sdk.agent.tools.support.PathPolicy;
import sdk.agent.tools.support.ToolException;
import sdk.agent.tools.support.Truncation;

/// Everything the four tools need from the host, as one injected object: the workspace and its
/// path policy, the session's file versions, the shell, the output limits and the two behavioural
/// switches. There is no static `current()` — the host constructs one and hands it to the tool pack.
public final class ToolEnvironment {

    private final Path workspace;
    private final PathPolicy paths;
    private final FileVersions versions = new FileVersions();
    private final Duration defaultCommandTimeout;
    private final Truncation.Limits limits;
    private final boolean lineNumbers;
    private final boolean readBeforeOverwrite;
    private final Clock clock;
    private volatile ShellSpec shell;                    // discovered on first use

    private ToolEnvironment(Builder b) {
        workspace = b.workspace; paths = b.paths != null ? b.paths : new PathPolicy(b.workspace);
        defaultCommandTimeout = b.defaultCommandTimeout; limits = b.limits;
        lineNumbers = b.lineNumbers; readBeforeOverwrite = b.readBeforeOverwrite; clock = b.clock; shell = b.shell;
    }

    public static Builder builder(Path workspace) { return new Builder(workspace); }

    /// Defaults for `workspace`: local shell discovery, 2-minute commands, read-before-overwrite on.
    public static ToolEnvironment local(Path workspace) { return builder(workspace).build(); }

    public Path workspace()                     { return workspace; }
    public PathPolicy paths()                   { return paths; }
    public FileVersions versions()              { return versions; }
    public Duration defaultCommandTimeout()     { return defaultCommandTimeout; }
    public Truncation.Limits limits()           { return limits; }
    /// Prefix `read` output with `N\t`. Off by default: `edit` needs byte-exact `oldText`, and
    /// numbered output invites the model to copy the numbers in.
    public boolean lineNumbers()                { return lineNumbers; }
    /// `write` refuses to overwrite a file this session has not read — a lost-update guard, not an authorisation check.
    public boolean readBeforeOverwrite()        { return readBeforeOverwrite; }
    public Clock clock()                        { return clock; }

    /// The shell, discovered lazily and memoised so a machine without bash can still build the
    /// environment (and use `read`/`write`/`edit`); the first `bash` call surfaces the explanation.
    /// @throws ToolException when no shell can be found
    public ShellSpec shell() {
        ShellSpec s = shell;
        if (s == null) {
            synchronized (this) {
                s = shell;
                if (s == null) shell = s = ShellDiscovery.platform().discover();
            }
        }
        return s;
    }

    public static final class Builder {
        private final Path workspace;
        private PathPolicy paths;
        private ShellSpec shell;
        private Duration defaultCommandTimeout = Duration.ofMinutes(2);
        private Truncation.Limits limits = Truncation.Limits.DEFAULT;
        private boolean lineNumbers;
        private boolean readBeforeOverwrite = true;
        private Clock clock = Clock.systemUTC();

        private Builder(Path workspace) { this.workspace = Objects.requireNonNull(workspace, "workspace").toAbsolutePath().normalize(); }

        public Builder paths(PathPolicy p)                     { paths = Objects.requireNonNull(p); return this; }
        public Builder shell(ShellSpec s)                      { shell = Objects.requireNonNull(s); return this; }
        public Builder defaultCommandTimeout(Duration d)       { defaultCommandTimeout = Objects.requireNonNull(d); return this; }
        public Builder limits(Truncation.Limits l)             { limits = Objects.requireNonNull(l); return this; }
        public Builder lineNumbers(boolean on)                 { lineNumbers = on; return this; }
        public Builder readBeforeOverwrite(boolean on)         { readBeforeOverwrite = on; return this; }
        public Builder clock(Clock c)                          { clock = Objects.requireNonNull(c); return this; }

        public ToolEnvironment build() { return new ToolEnvironment(this); }
    }
}
