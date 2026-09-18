package sdk.agent.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import sdk.agent.prompt.SectionSpec;
import sdk.agent.spi.Contributions;
import sdk.agent.spi.Extension;

/// Basic workspace tools. Filesystem containment is a guard, not an OS sandbox.
public final class CodingToolsExtension implements Extension {
    private static final int PROJECT_LIMIT = 32 * 1024;
    private final ToolEnvironment environment;

    public CodingToolsExtension() throws IOException { this(new ToolEnvironment(Path.of("."))); }

    public CodingToolsExtension(ToolEnvironment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override public String id() { return "coding-tools"; }

    @Override public Contributions contributions() {
        return Contributions.builder()
                .tools(List.of(new ReadTool(environment), new WriteTool(environment),
                        new EditTool(environment), new BashTool(environment)))
                .promptContributor(() -> List.of(
                        SectionSpec.always("coding.base", 0,
                                "You are a coding agent. Inspect relevant files before modifying them. "
                                + "Make focused changes and verify their behavior."),
                        new SectionSpec("coding.tools", 10, _ -> true, context -> context.tools().stream()
                                .flatMap(tool -> tool.promptGuidelines().stream()).distinct()
                                .collect(Collectors.joining("\n", "# Tool usage\n", ""))),
                        SectionSpec.always("coding.workspace", 20,
                                "# Workspace\n" + environment.workspace()
                                + "\nShell execution is unsandboxed. Host permission policy and OS isolation "
                                + "must authorize and restrict commands; filesystem tool guards do not constrain the shell."),
                        new SectionSpec("coding.project", 30, _ -> true, _ -> projectInstructions())))
                .build();
    }

    private String projectInstructions() {
        var text = new StringBuilder();
        for (String name : List.of("AGENTS.md", "CLAUDE.md")) {
            Path path = environment.workspace().resolve(name);
            try {
                Path real = path.toRealPath();
                if (!real.startsWith(environment.workspace())) {
                    throw new IOException("Project instructions escape workspace: " + path);
                }
                byte[] bytes;
                try (var input = Files.newInputStream(real)) { bytes = input.readNBytes(PROJECT_LIMIT + 1); }
                if (bytes.length > PROJECT_LIMIT) throw new IOException("Project instructions exceed 32 KiB: " + path);
                var decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes));
                if (!text.isEmpty()) text.append('\n');
                text.append("# ").append(name).append('\n').append(decoded);
            } catch (NoSuchFileException missing) {
                // Only absent entries are optional; a broken symlink remains a visible failure.
                if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new UncheckedIOException(missing);
            } catch (IOException failure) {
                throw new UncheckedIOException("Cannot read project instructions " + path, failure);
            }
        }
        return text.toString();
    }
}
