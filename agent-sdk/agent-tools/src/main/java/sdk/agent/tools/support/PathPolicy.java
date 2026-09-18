package sdk.agent.tools.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/// Path containment: resolve-then-contain, relative-against-root, deepest-existing-ancestor probe,
/// one gate. Containment is not permission — it answers "is this path inside the workspace", not
/// "may the model touch it".
///
/// `toRealPath()` throws on a non-existent path (every write target) and `normalize()` is lexical.
/// So: normalise, walk up to the deepest existing ancestor, canonicalise that, compare against the
/// canonical root, and refuse any `..` left in the non-existent tail. The guarantee is precise: *the
/// returned path is inside the root, and it is the path the OS will open*. Never `String.startsWith`
/// on path text — `/workspace-evil` starts with `/workspace`.
public final class PathPolicy {

    /// Mutating operations may never target these, at any depth: `.git/hooks/pre-commit` is code execution.
    public static final Set<String> DENIED_FOR_WRITE = Set.of(".git", ".hg", ".svn");

    public static final String OUTSIDE = "Path is outside the workspace root: ";

    private static final Pattern UNICODE_SPACES = Pattern.compile("[\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]");

    private final Path root;
    private final Set<String> deniedForWrite;

    public PathPolicy(Path root) { this(root, DENIED_FOR_WRITE); }

    public PathPolicy(Path root, Set<String> deniedForWrite) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.deniedForWrite = Set.copyOf(deniedForWrite);
    }

    public Path root() { return root; }

    /// Strip a leading `@`, squash the Unicode space set to U+0020, expand `~` and `~/` (not `~user/`).
    public static String expand(String raw) {
        String s = raw.startsWith("@") ? raw.substring(1) : raw;
        s = UNICODE_SPACES.matcher(s).replaceAll(" ");
        String home = System.getProperty("user.home");
        if (s.equals("~")) return home;
        if (s.startsWith("~/")) return home + s.substring(1);
        return s;
    }

    /// @throws ToolException if the path is malformed or escapes the root
    /// @throws IOException if canonicalising an existing ancestor fails
    public Path resolve(String raw) throws IOException {
        Objects.requireNonNull(raw, "path");
        Path p;
        try {
            p = Path.of(expand(raw));
        } catch (InvalidPathException _) {
            throw ToolException.invalid(OUTSIDE + raw);                 // Windows: ':' or '*' in a name
        }
        p = (p.isAbsolute() ? p : root.resolve(p)).normalize();

        Path probe = p;
        while (!Files.exists(probe, LinkOption.NOFOLLOW_LINKS) && probe.getParent() != null) probe = probe.getParent();

        Path realRoot = realRoot();
        Path realProbe = probe.toRealPath();
        if (!realProbe.startsWith(realRoot)) throw ToolException.invalid(OUTSIDE + raw);

        Path tail = probe.relativize(p);
        for (Path segment : tail) {
            if (segment.toString().equals("..")) throw ToolException.invalid(OUTSIDE + raw);
        }
        return tail.toString().isEmpty() ? realProbe : realProbe.resolve(tail);
    }

    /// [#resolve] plus the write deny-set, checked on every segment of the **resolved** path below the root.
    public Path resolveForWrite(String raw) throws IOException {
        Path resolved = resolve(raw);
        Path relative = realRoot().relativize(resolved);
        for (Path segment : relative) {
            if (deniedForWrite.contains(segment.toString())) throw ToolException.invalid(OUTSIDE + raw);
        }
        return resolved;
    }

    /// Creates a missing parent directory **only** when it is missing and inside the root — never
    /// an unconditional `mkdir -p` that turns a typo into a directory tree.
    public void createParentsWithin(Path resolved) throws IOException {
        Path parent = resolved.getParent();
        if (parent == null || Files.isDirectory(parent)) return;
        Path probe = parent;
        while (!Files.exists(probe, LinkOption.NOFOLLOW_LINKS) && probe.getParent() != null) probe = probe.getParent();
        if (!probe.toRealPath().startsWith(realRoot())) throw ToolException.invalid(OUTSIDE + resolved);
        Files.createDirectories(parent);
    }

    private Path realRoot() throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("workspace root does not exist: " + root);
        return root.toRealPath();
    }
}
