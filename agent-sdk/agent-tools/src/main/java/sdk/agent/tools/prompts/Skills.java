package sdk.agent.tools.prompts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import sdk.agent.prompt.PromptTemplate;

/// kon's skills: a directory with a `SKILL.md` — frontmatter (`name`, `description`, `register_cmd`,
/// `cmd_info`) and instructions the model reads when a task matches. Found in `.agents/skills/` from
/// the workspace up to the git root, then `~/.agents/skills/`, then the built-ins; the first name wins.
/// A skill with `register_cmd` is also a slash command, expanded by [#command].
public record Skills(List<Skill> skills, List<String> warnings) {

    public static final String FILE_NAME = "SKILL.md";
    public static final Path SKILLS_DIR = Path.of(".agents", "skills");

    /// Shipped as resources, commands only: `/init` and `/review`.
    public static final List<String> BUILTIN = List.of("init", "review");

    static final int MAX_NAME_LENGTH = 64;
    static final int MAX_DESCRIPTION_LENGTH = 1024;
    static final int MAX_CMD_INFO_LENGTH = 32;

    private static final PromptTemplate TEMPLATE = Templates.load("skills.md");
    private static final Pattern NAME = Pattern.compile("[a-z0-9-]+");
    private static final Pattern FRONTMATTER_END = Pattern.compile("\\n---\\s*\\n");
    private static final Set<String> TRUE = Set.of("1", "true", "yes", "on");
    private static final String RESOURCE_ROOT = "/sdk/agent/tools/prompts/";

    public Skills {
        skills = List.copyOf(skills);
        warnings = List.copyOf(warnings);
    }

    public static Skills load(Path workspace) {
        return load(workspace, Path.of(System.getProperty("user.home", ".")).resolve(SKILLS_DIR));
    }

    static Skills load(Path workspace, Path userSkills) {
        Path cwd = workspace.toAbsolutePath().normalize();
        Path stop = GitContext.root(cwd).orElse(cwd);
        var dirs = new ArrayList<Path>();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            dirs.add(dir.resolve(SKILLS_DIR));
            if (dir.equals(stop)) break;
        }
        Path user = userSkills.toAbsolutePath().normalize();
        if (!dirs.contains(user)) dirs.add(user);

        var byName = new LinkedHashMap<String, Skill>();
        var warnings = new ArrayList<String>();
        for (Path dir : dirs) {
            for (Skill skill : loadDirectory(dir, warnings)) {
                Skill previous = byName.putIfAbsent(skill.name(), skill);
                if (previous != null) {
                    warnings.add(skill.location() + ": name collision: \"" + skill.name() + "\" already loaded from " + previous.location());
                }
            }
        }
        for (Skill skill : builtin().skills()) byName.putIfAbsent(skill.name(), skill);
        return new Skills(List.copyOf(byName.values()), warnings);
    }

    /// The built-in command skills; a missing resource is a packaging defect.
    public static Skills builtin() {
        var skills = new ArrayList<Skill>();
        var warnings = new ArrayList<String>();
        for (String name : BUILTIN) {
            String resource = "skills/" + name + "/" + FILE_NAME;
            parse(Templates.load(resource).text(), name, "classpath:" + RESOURCE_ROOT + resource, warnings).ifPresent(skills::add);
        }
        return new Skills(skills, warnings);
    }

    public Optional<Skill> find(String name) {
        return skills.stream().filter(s -> s.name().equals(name)).findFirst();
    }

    /// `skills.md` for the system prompt, or `""` when nothing is listed.
    public String render() {
        List<Skill> listed = skills.stream().filter(Skill::includeInPrompt).toList();
        if (listed.isEmpty()) return "";
        String entries = listed.stream()
                .map(s -> "<skill>\n<name>" + Xml.escape(s.name()) + "</name>\n<description>" + Xml.escape(s.description())
                        + "</description>\n<path>" + Xml.escape(s.location()) + "</path>\n</skill>")
                .collect(Collectors.joining("\n"));
        return TEMPLATE.render(Map.of("skills", entries));
    }

    /// kon's slash commands: `/name args` → the registered skill's prompt; empty for anything else.
    public Optional<String> command(String input) {
        String text = input.strip();
        if (!text.startsWith("/")) return Optional.empty();
        String[] parts = text.substring(1).split("\\s+", 2);
        return find(parts[0]).filter(Skill::registerCmd).map(s -> s.render(parts.length > 1 ? parts[1] : ""));
    }

    // ---- discovery and parsing --------------------------------------------------------------

    private static List<Skill> loadDirectory(Path dir, List<String> warnings) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isDirectory)
                    .filter(e -> !e.getFileName().toString().startsWith("."))
                    .sorted()
                    .flatMap(e -> read(e, warnings).stream())
                    .toList();
        } catch (IOException e) {
            warnings.add(dir + ": " + e.getMessage());
            return List.of();
        }
    }

    private static Optional<Skill> read(Path skillDir, List<String> warnings) {
        Path file = skillDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8), skillDir.getFileName().toString(), file.toString(), warnings);
        } catch (IOException e) {
            warnings.add(file + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /// A blank description drops the skill; every other problem is a warning and the skill loads.
    static Optional<Skill> parse(String content, String directoryName, String location, List<String> warnings) {
        Map<String, String> meta = frontmatter(content);
        String name = meta.getOrDefault("name", "").isBlank() ? directoryName : meta.get("name");
        String description = meta.getOrDefault("description", "");
        String registerCmd = meta.getOrDefault("register_cmd", "").strip().toLowerCase(Locale.ROOT);
        boolean commandOnly = registerCmd.equals("only");
        String cmdInfo = meta.getOrDefault("cmd_info", "").strip();

        warnings.addAll(validate(name, description, directoryName, location, cmdInfo));
        if (description.isBlank()) return Optional.empty();
        return Optional.of(new Skill(name, description, location, stripFrontmatter(content),
                                     commandOnly || TRUE.contains(registerCmd), cmdInfo, !commandOnly));
    }

    static List<String> validate(String name, String description, String directoryName, String location, String cmdInfo) {
        var problems = new ArrayList<String>();
        if (!name.equals(directoryName)) problems.add("name \"" + name + "\" does not match directory \"" + directoryName + "\"");
        if (name.length() > MAX_NAME_LENGTH) problems.add("name exceeds " + MAX_NAME_LENGTH + " characters");
        if (!NAME.matcher(name).matches()) problems.add("name must be lowercase a-z, 0-9, hyphens only");
        if (name.startsWith("-") || name.endsWith("-")) problems.add("name must not start or end with hyphen");
        if (name.contains("--")) problems.add("name must not contain consecutive hyphens");
        if (description.isBlank()) problems.add("description is required");
        if (description.length() > MAX_DESCRIPTION_LENGTH) problems.add("description exceeds " + MAX_DESCRIPTION_LENGTH + " characters");
        if (cmdInfo.length() > MAX_CMD_INFO_LENGTH) problems.add("cmd_info exceeds " + MAX_CMD_INFO_LENGTH + " characters");
        return problems.stream().map(p -> location + ": " + p).toList();
    }

    /// `---` … `---`, one `key: value` per line, `#` comments, optional quotes.
    static Map<String, String> frontmatter(String content) {
        var result = new LinkedHashMap<String, String>();
        if (!content.startsWith("---")) return result;
        Matcher end = FRONTMATTER_END.matcher(content);
        if (!end.find(3)) return result;
        for (String raw : content.substring(3, end.start()).split("\n")) {
            String line = raw.strip();
            int colon = line.indexOf(':');
            if (line.isEmpty() || line.startsWith("#") || colon < 0) continue;
            String value = stripInlineComment(line.substring(colon + 1).strip());
            if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                    && value.charAt(value.length() - 1) == value.charAt(0)) {
                value = value.substring(1, value.length() - 1);
            }
            result.put(line.substring(0, colon).strip(), value);
        }
        return result;
    }

    static String stripFrontmatter(String content) {
        if (!content.startsWith("---")) return content.strip();
        Matcher end = FRONTMATTER_END.matcher(content);
        return end.find(3) ? content.substring(end.end()).strip() : content.strip();
    }

    /// A `#` outside quotes, at the start or after whitespace, begins a comment.
    private static String stripInlineComment(String value) {
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && quote != 0) {
                escaped = true;
                continue;
            }
            if (c == '"' || c == '\'') {
                if (quote == 0) quote = c;
                else if (quote == c) quote = 0;
                continue;
            }
            if (c == '#' && quote == 0 && (i == 0 || Character.isWhitespace(value.charAt(i - 1)))) {
                return value.substring(0, i).stripTrailing();
            }
        }
        return value;
    }
}
