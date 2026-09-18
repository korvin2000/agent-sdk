package sdk.agent.tools.prompts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import sdk.agent.prompt.PromptTemplate;

/// kon's `# Project Context`: one `AGENTS.md` (or `CLAUDE.md`) per directory from the git root
/// (else the home directory, else the workspace) down to the workspace, outermost first.
public record AgentsMd(List<ContextFile> files) {

    /// Checked in this order; the first that exists in a directory is loaded.
    public static final List<String> FILE_NAMES = List.of("AGENTS.md", "CLAUDE.md");

    private static final PromptTemplate TEMPLATE = Templates.load("project-context.md");

    public record ContextFile(Path path, String content) { }

    public AgentsMd { files = List.copyOf(files); }

    /// Never throws: a file that is not readable UTF-8 is skipped.
    public static AgentsMd load(Path workspace) {
        Path cwd = workspace.toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        Path stop = GitContext.root(cwd).orElse(cwd.startsWith(home) ? home : cwd);

        var found = new ArrayList<ContextFile>();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            read(dir).ifPresent(found::addFirst);
            if (dir.equals(stop)) break;
        }
        return new AgentsMd(found);
    }

    /// `project-context.md`, or `""` without any file.
    public String render() {
        if (files.isEmpty()) return "";
        String entries = files.stream()
                .map(f -> "<file path=\"" + Xml.escape(f.path().toString()) + "\">\n" + Xml.escape(f.content()) + "\n</file>")
                .collect(Collectors.joining("\n"));
        return TEMPLATE.render(Map.of("files", entries));
    }

    private static Optional<ContextFile> read(Path dir) {
        for (String name : FILE_NAMES) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            try {
                return Optional.of(new ContextFile(file, Files.readString(file, StandardCharsets.UTF_8)));
            } catch (IOException _) {
                // skipped, as kon does; the next candidate name may still load
            }
        }
        return Optional.empty();
    }
}
