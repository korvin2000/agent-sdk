package sdk.agent.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import sdk.agent.spi.Contributions;
import sdk.agent.spi.Extension;
import sdk.agent.tools.fs.EditTool;
import sdk.agent.tools.fs.ReadTool;
import sdk.agent.tools.fs.WriteTool;
import sdk.agent.tools.prompts.CodingPrompts;
import sdk.agent.tools.shell.BashTool;

/// The base tool pack — `read`, `bash`, `edit`, `write`, in that model-visible order — plus the
/// [CodingPrompts] system prompt. The no-argument constructor serves `ServiceLoader` discovery.
public final class CodingToolsExtension implements Extension {

    public static final String ID = CodingPrompts.CONTRIBUTOR_ID;

    private final ToolEnvironment env;
    private final CodingPrompts prompts;

    public CodingToolsExtension() { this(ToolEnvironment.local(Path.of("").toAbsolutePath())); }

    public CodingToolsExtension(ToolEnvironment env) { this(env, new CodingPrompts(env)); }

    /// For a host with its own base text or without the git snapshot.
    public CodingToolsExtension(ToolEnvironment env, CodingPrompts prompts) {
        this.env = Objects.requireNonNull(env, "env");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
    }

    public ToolEnvironment environment() { return env; }

    @Override public String id() { return ID; }

    @Override public Contributions contributions() {
        return Contributions.builder()
                .tools(List.of(new ReadTool(env), new BashTool(env), new EditTool(env), new WriteTool(env)))
                .promptContributor(prompts)
                .build();
    }
}
