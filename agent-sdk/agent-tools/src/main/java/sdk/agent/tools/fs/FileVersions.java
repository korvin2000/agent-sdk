package sdk.agent.tools.fs;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import sdk.agent.tool.Digests;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tools.support.ToolException;

/// The session's view of the files it has touched: which paths were read (the lost-update guard in
/// `write`) and the hash of the bytes last seen (the compare-and-swap behind `edit`). Four rules:
/// hash the bytes you are about to act on, in the same operation — never mtime or size; refuse
/// before mutating; swap atomically through a temp file in the target's directory; hash the
/// **bytes**, so a CRLF or BOM change is a change. The session, not the model, carries the
/// version: there is no `expectedVersion` parameter for the model to get wrong.
public final class FileVersions {

    public static final String STALE =
            "%s changed on disk since you read it. Nothing was written. Read it again and reissue the edit against the current contents.";

    /// `version` is 12 hex of SHA-256 over `bytes`.
    public record Snapshot(byte[] bytes, String version) { }

    private final ConcurrentMap<String, String> versions = new ConcurrentHashMap<>();

    public static String versionOf(byte[] bytes) { return Digests.sha256Hex(bytes).substring(0, 12); }

    /// The version for a SHA-256 computed while streaming.
    public static String fromDigest(byte[] sha256) { return HexFormat.of().formatHex(sha256).substring(0, 12); }

    /// Read and hash in one operation, and remember the version as the session's view of the file.
    public Snapshot read(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        String version = versionOf(bytes);
        versions.put(key(p), version);
        return new Snapshot(bytes, version);
    }

    /// Whether this session has read any part of `p`, or written it.
    public boolean seen(Path p) { return versions.containsKey(key(p)); }

    /// Record what a read showed. `version` hashes every byte the read consumed to EOF — the whole
    /// file, whatever window was shown — so a later `edit` can tell "changed on disk since you read it".
    public void markSeen(Path p, String version) { versions.put(key(p), Objects.requireNonNull(version, "version")); }

    /// The version recorded at the last read or write of `p`, or null if none.
    public String recordedVersion(Path p) { return versions.get(key(p)); }

    /// Atomic write — temp file in the **target's** directory (an atomic move only works within one
    /// filesystem), then move-replace, so a crash leaves the old file or the new one, never a
    /// truncated one — and record. Returns the new version.
    public String write(Path target, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(target.toAbsolutePath().getParent(), ".agent-write-", ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        String version = versionOf(bytes);
        versions.put(key(target), version);
        return version;
    }

    /// Re-verify that `p` still hashes to `expected` (`null`: nothing to verify), then [#write].
    /// The residual window is the rename itself — documented, not pretended closed.
    /// @throws ToolException naming the stale file; nothing is written
    public String commit(Path p, byte[] bytes, String expected, String asWritten) throws IOException {
        if (expected != null && (!Files.exists(p) || !versionOf(Files.readAllBytes(p)).equals(expected))) throw stale(asWritten);
        return write(p, bytes);
    }

    public static ToolException stale(String asWritten) {
        return new ToolException(ErrorKind.INVALID_ARGUMENTS, STALE.formatted(asWritten));
    }

    /// Forget everything about `p` (a host that deletes or externally rewrites a file).
    public void forget(Path p) { versions.remove(key(p)); }

    private static String key(Path p) {
        try {
            return Files.exists(p) ? p.toRealPath().toString() : p.toAbsolutePath().normalize().toString();
        } catch (IOException _) {
            return p.toAbsolutePath().normalize().toString();
        }
    }
}
