package sdk.agent.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import sdk.agent.tool.ErrorKind;

/// The one filesystem boundary for an environment. Every operation is synchronized so the
/// observation cache and its recheck have one coherent owner.
final class FileAccess {

    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    static final int MAX_CACHE_ENTRIES = 4096;

    record Failure(ErrorKind kind, String message) { }
    record FileData(Path path, byte[] bytes, String text, String mimeType, String digest) { }
    record ReadResult(FileData data, Failure failure) { }
    record ReplaceResult(Path path, int byteCount, Failure failure) { }

    private final Path workspace;
    private final LinkedHashMap<Path, byte[]> observations = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Path, byte[]> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    FileAccess(Path workspace) { this.workspace = workspace; }

    /// Loads a bounded file but deliberately does not grant a later write authority.
    synchronized ReadResult read(String supplied) throws IOException {
        final Path path;
        try {
            path = resolve(supplied, false);
        } catch (PathRefusal e) {
            return refusedRead(e);
        }
        return load(supplied, path);
    }

    /// Remembers a successfully presented file version under its canonical identity.
    synchronized void observe(FileData data) {
        Objects.requireNonNull(data, "data");
        observations.put(data.path(), sha256(data.bytes()));
    }

    /// Loads an already observed file only if its raw bytes still match that observation.
    synchronized ReadResult readForEdit(String supplied) throws IOException {
        final Path path;
        try {
            path = resolve(supplied, true);
        } catch (PathRefusal e) {
            return refusedRead(e);
        }
        byte[] expected = observations.get(path);
        if (expected == null) {
            return new ReadResult(null, new Failure(ErrorKind.BLOCKED,
                    "file was not observed or observation was evicted; reread it first"));
        }
        if (!Files.isRegularFile(path)) {
            return new ReadResult(null, new Failure(ErrorKind.BLOCKED, "file was deleted since it was read; reread it first"));
        }
        final byte[] bytes;
        try {
            bytes = readBounded(path, MAX_BYTES);
        } catch (SizeRefusal e) {
            return new ReadResult(null, changedFailure());
        }
        if (!Arrays.equals(expected, sha256(bytes))) return new ReadResult(null, changedFailure());
        return decodeLoaded(supplied, path, bytes);
    }

    synchronized ReplaceResult replace(String supplied, byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_BYTES) return failure(null, ErrorKind.INVALID_ARGUMENTS, "file exceeds 8 MiB UTF-8 limit");
        final Path path;
        try {
            path = resolve(supplied, true);
        } catch (PathRefusal e) {
            return failure(null, e.failure.kind(), e.failure.message());
        }
        byte[] expected = observations.get(path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (expected == null) return blocked(path, "file was not observed or observation was evicted; reread it first");
            final byte[] actual;
            try {
                actual = readBounded(path, MAX_BYTES);
            } catch (SizeRefusal e) {
                return blocked(path, "file changed since it was read; reread it first");
            }
            if (!Arrays.equals(expected, sha256(actual))) return blocked(path, "file changed since it was read; reread it first");
            try {
                return replaceExisting(path, bytes, expected);
            } catch (PathRefusal e) {
                return failure(path, e.failure.kind(), e.failure.message());
            }
        }
        if (expected != null) return blocked(path, "file was deleted since it was read; reread it first");
        try {
            return createNew(path, bytes);
        } catch (PathRefusal e) {
            return failure(path, e.failure.kind(), e.failure.message());
        }
    }

    private ReadResult load(String supplied, Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return new ReadResult(null, new Failure(ErrorKind.INVALID_ARGUMENTS, "not a regular file: " + supplied));
        }
        final byte[] bytes;
        try {
            bytes = readBounded(path, MAX_BYTES);
        } catch (SizeRefusal e) {
            return new ReadResult(null, new Failure(ErrorKind.INVALID_ARGUMENTS, e.getMessage()));
        }
        return decodeLoaded(supplied, path, bytes);
    }

    private ReadResult decodeLoaded(String supplied, Path path, byte[] bytes) {
        String mime = imageMime(bytes);
        if (mime != null) {
            if (bytes.length > MAX_IMAGE_BYTES) {
                return new ReadResult(null, new Failure(ErrorKind.INVALID_ARGUMENTS, "image exceeds 5 MiB: " + supplied));
            }
            return new ReadResult(new FileData(path, bytes, null, mime, hex(sha256(bytes))), null);
        }
        try {
            String text = decode(bytes);
            return new ReadResult(new FileData(path, bytes, text, null, hex(sha256(bytes))), null);
        } catch (CharacterCodingException e) {
            return new ReadResult(null, new Failure(ErrorKind.INVALID_ARGUMENTS, "file is not valid UTF-8 text: " + supplied));
        }
    }

    private ReplaceResult replaceExisting(Path path, byte[] bytes, byte[] expected) throws IOException, PathRefusal {
        Path parent = prepareParent(path.getParent());
        Path temp = null;
        try {
            temp = Files.createTempFile(parent, "." + path.getFileName() + ".", ".tmp");
            copyPermissions(path, temp);
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                if (!unchanged(path, expected)) return blocked(path, "file changed while writing; reread it first");
            } catch (SizeRefusal e) {
                return blocked(path, "file changed while writing; reread it first");
            }
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
                return failure(path, ErrorKind.UNAVAILABLE, "atomic replacement is unavailable");
            }
            observations.put(path, sha256(bytes));
            return new ReplaceResult(path, bytes.length, null);
        } finally {
            if (temp != null) Files.deleteIfExists(temp);
        }
    }

    private boolean unchanged(Path path, byte[] expected) throws IOException, SizeRefusal {
        return Arrays.equals(expected, sha256(readBounded(path, MAX_BYTES)));
    }

    private ReplaceResult createNew(Path path, byte[] bytes) throws IOException, PathRefusal {
        Path parent = prepareParent(path.getParent());
        Path temp = null;
        try {
            temp = Files.createTempFile(parent, "." + path.getFileName() + ".", ".tmp");
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            prepareParent(parent);
            try {
                Files.move(temp, path);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                return blocked(path, "file appeared while writing; reread it first");
            }
            observations.put(path, sha256(bytes));
            return new ReplaceResult(path, bytes.length, null);
        } finally {
            if (temp != null) Files.deleteIfExists(temp);
        }
    }

    private Path resolve(String supplied, boolean forWrite) throws IOException, PathRefusal {
        if (supplied == null || supplied.isBlank() || supplied.indexOf('\0') >= 0) {
            throw refuse(ErrorKind.INVALID_ARGUMENTS, "path must be nonblank and contain no NUL");
        }
        final Path raw;
        try {
            raw = Path.of(supplied);
        } catch (RuntimeException e) {
            throw refuse(ErrorKind.INVALID_ARGUMENTS, "invalid path: " + supplied);
        }
        if (forWrite && vcs(raw)) throw refuse(ErrorKind.BLOCKED, "access to repository metadata is blocked");
        Path lexical = (raw.isAbsolute() ? raw : workspace.resolve(raw)).normalize();
        if (!lexical.startsWith(workspace)) throw refuse(ErrorKind.BLOCKED, "path escapes the workspace");
        Path existing = lexical;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            if (existing == null) throw refuse(ErrorKind.BLOCKED, "path has no existing parent");
        }
        Path existingReal;
        try {
            existingReal = existing.toRealPath();
        } catch (java.nio.file.NoSuchFileException e) {
            throw refuse(ErrorKind.BLOCKED, "path contains a dangling symbolic link");
        }
        if (!existingReal.startsWith(workspace)) throw refuse(ErrorKind.BLOCKED, "path resolves outside the workspace");
        Path path = existing.equals(lexical) ? existingReal : existingReal.resolve(existing.relativize(lexical)).normalize();
        if (!path.startsWith(workspace)) throw refuse(ErrorKind.BLOCKED, "path resolves outside the workspace");
        if (forWrite && vcs(path)) throw refuse(ErrorKind.BLOCKED, "access to repository metadata is blocked");
        return path;
    }

    /// Creates missing parents only after their nearest existing ancestor has been checked.
    private Path prepareParent(Path parent) throws IOException, PathRefusal {
        if (parent == null) throw refuse(ErrorKind.BLOCKED, "missing parent directory");
        Path existing = parent;
        List<Path> missing = new ArrayList<>();
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(existing);
            existing = existing.getParent();
            if (existing == null) throw refuse(ErrorKind.BLOCKED, "parent resolves outside the workspace");
        }
        final Path real;
        try {
            real = existing.toRealPath();
        } catch (java.nio.file.NoSuchFileException e) {
            throw refuse(ErrorKind.BLOCKED, "parent contains a dangling symbolic link");
        }
        if (!real.startsWith(workspace) || vcs(real)) {
            throw refuse(ErrorKind.BLOCKED, "parent resolves outside the workspace or into repository metadata");
        }
        for (int i = missing.size() - 1; i >= 0; i--) {
            Path created = missing.get(i);
            Files.createDirectory(created);
            Path createdReal = created.toRealPath();
            if (!createdReal.startsWith(workspace) || vcs(createdReal)) {
                throw refuse(ErrorKind.BLOCKED, "parent resolves outside the workspace or into repository metadata");
            }
        }
        Path resolved = parent.toRealPath();
        if (!resolved.startsWith(workspace) || vcs(resolved)) {
            throw refuse(ErrorKind.BLOCKED, "parent resolves outside the workspace or into repository metadata");
        }
        return resolved;
    }

    private static boolean vcs(Path path) {
        for (Path part : path) {
            String name = part.toString();
            if (name.equals(".git") || name.equals(".hg") || name.equals(".svn")) return true;
        }
        return false;
    }

    private static byte[] readBounded(Path path, int limit) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192));
            byte[] buffer = new byte[Math.min(8192, limit + 1)];
            int total = 0;
            while (total <= limit) {
                int n = in.read(buffer, 0, Math.min(buffer.length, limit + 1 - total));
                if (n < 0) return out.toByteArray();
                out.write(buffer, 0, n);
                total += n;
            }
            throw new SizeRefusal(limit);
        }
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        String text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            if (codePoint == 0 || Character.isISOControl(codePoint) && codePoint != '\t' && codePoint != '\n' && codePoint != '\r') {
                throw new InvalidText();
            }
            i += Character.charCount(codePoint);
        }
        return text;
    }

    static byte[] encode(String text) throws CharacterCodingException {
        Objects.requireNonNull(text, "text");
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }


    static long utf8Length(String text) throws CharacterCodingException {
        Objects.requireNonNull(text, "text");
        long bytes = 0;
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) throw new InvalidText();
                bytes += 4;
                i += 2;
            } else if (Character.isLowSurrogate(c)) {
                throw new InvalidText();
            } else {
                bytes += c <= 0x7f ? 1 : c <= 0x7ff ? 2 : 3;
                i++;
            }
        }
        return bytes;
    }
    static String utf8Prefix(String text, int byteLimit) {
        Objects.requireNonNull(text, "text");
        if (byteLimit <= 0) return "";
        int bytes = 0, end = 0;
        while (end < text.length()) {
            char first = text.charAt(end);
            int width;
            if (Character.isHighSurrogate(first)) {
                if (end + 1 == text.length() || !Character.isLowSurrogate(text.charAt(end + 1))) break;
                width = 4;
            } else if (Character.isLowSurrogate(first)) {
                break;
            } else if (first <= 0x7f) {
                width = 1;
            } else if (first <= 0x7ff) {
                width = 2;
            } else {
                width = 3;
            }
            if (bytes + width > byteLimit) break;
            bytes += width;
            end += Character.charCount(text.codePointAt(end));
        }
        return text.substring(0, end);
    }

    static String withoutBom(String text) { return text.startsWith("\uFEFF") ? text.substring(1) : text; }

    static String imageMime(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 255) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a) return "image/png";
        if (bytes.length >= 3 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8 && (bytes[2] & 255) == 0xff) return "image/jpeg";
        if (bytes.length >= 6 && new String(bytes, 0, 6, StandardCharsets.US_ASCII).matches("GIF8[79]a")) return "image/gif";
        if (bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        return null;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }

    private static void copyPermissions(Path source, Path target) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(source, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) return;
        Set<PosixFilePermission> permissions = view.readAttributes().permissions();
        Files.setPosixFilePermissions(target, permissions);
    }

    private static ReadResult refusedRead(PathRefusal e) { return new ReadResult(null, e.failure); }

    private static Failure changedFailure() { return new Failure(ErrorKind.BLOCKED, "file changed since it was read; reread it first"); }

    private static ReplaceResult blocked(Path path, String message) { return failure(path, ErrorKind.BLOCKED, message); }

    private static ReplaceResult failure(Path path, ErrorKind kind, String message) {
        return new ReplaceResult(path, 0, new Failure(kind, message));
    }

    private static PathRefusal refuse(ErrorKind kind, String message) { return new PathRefusal(new Failure(kind, message)); }

    private static final class PathRefusal extends Exception {
        final Failure failure;
        PathRefusal(Failure failure) { this.failure = failure; }
    }
    private static final class InvalidText extends CharacterCodingException { }

    private static final class SizeRefusal extends IOException {
        SizeRefusal(int limit) { super("file exceeds " + limit + " byte limit"); }
    }
}
