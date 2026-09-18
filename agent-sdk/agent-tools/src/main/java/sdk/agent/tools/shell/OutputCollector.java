package sdk.agent.tools.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Objects;

/// One stream of a command. Bytes are accumulated and decoded once at EOF; cutting at the byte
/// budget can bisect a UTF-8 sequence, so the retained length walks back over continuation bytes
/// and drops the partial one. The true totals are counted as data arrives, independently of the
/// bounded window, so a notice names the size of the whole output. The spill file is an
/// optimisation, never a correctness requirement: a full disk or a locked temp directory is
/// reported, not thrown.
///
/// Every method is synchronised: the pump that fills it may be abandoned by the 100 ms post-exit
/// grace while `finish` is already reading.
final class OutputCollector {

    /// Five times the largest window the model can ever be shown, which leaves head room for the
    /// spill copy while keeping the per-command allocation bounded.
    static final int PER_STREAM_BUDGET = 256 * 1024;

    /// Appended inline exactly once, so the marker survives the later tail-keeping truncation.
    static final String STDOUT_MARKER = "\n... [Output truncated to prevent memory exhaustion]";
    static final String STDERR_MARKER = "\n... [Stderr truncated to prevent memory exhaustion]";

    private final String marker;
    private final ByteArrayOutputStream retained = new ByteArrayOutputStream();
    private long trueBytes;
    private long newlines;
    private boolean endsWithNewline = true;
    private boolean overflowed;
    private String decoded;

    private OutputCollector(String marker) { this.marker = marker; }

    static OutputCollector stdout() { return new OutputCollector(STDOUT_MARKER); }

    static OutputCollector stderr() { return new OutputCollector(STDERR_MARKER); }

    /// Called from the pump thread for every chunk read.
    synchronized void accept(byte[] buf, int len) {
        if (len <= 0) return;
        for (int i = 0; i < len; i++) {
            if (buf[i] == '\n') newlines++;
        }
        trueBytes += len;
        endsWithNewline = buf[len - 1] == '\n';
        int room = PER_STREAM_BUDGET - retained.size();
        if (room <= 0) { overflowed = true; return; }
        retained.write(buf, 0, Math.min(room, len));
        if (len > room) overflowed = true;
    }

    /// The decoded window, with the memory-exhaustion marker when the budget was hit. Decoded once.
    synchronized String text() {
        if (decoded == null) {
            byte[] bytes = retained.toByteArray();
            int len = overflowed ? completeUtf8Length(bytes, bytes.length) : bytes.length;
            String window = new String(bytes, 0, len, StandardCharsets.UTF_8);
            decoded = overflowed ? window + marker : window;
        }
        return decoded;
    }

    /// The retained bytes exactly as they arrived — the spill file's content, never sanitised.
    synchronized byte[] rawBytes() { return retained.toByteArray(); }

    synchronized long trueBytes() { return trueBytes; }

    synchronized int trueLines() { return lineCount(newlines, trueBytes, endsWithNewline); }

    synchronized boolean overflowed() { return overflowed; }

    /// `Truncation.lines` semantics: "a\nb\n" is two lines, "" is zero.
    private static int lineCount(long newlines, long bytes, boolean endsWithNewline) {
        long lines = newlines + (bytes > 0 && !endsWithNewline ? 1 : 0);
        return (int) Math.min(Integer.MAX_VALUE, lines);
    }

    /// The largest prefix of `b[0..len)` that ends on a UTF-8 character boundary: walk back over
    /// continuation bytes and drop the lead byte whose sequence the cut bisected.
    static int completeUtf8Length(byte[] b, int len) {
        int i = Math.min(len, b.length);
        int end = i;
        while (i > 0 && (b[i - 1] & 0xC0) == 0x80) i--;
        if (i == 0) return end;
        int lead = b[i - 1] & 0xFF;
        if (lead < 0xC0) return end;                       // ASCII lead, or a stray continuation: nothing straddles
        int need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : 2;
        return end - (i - 1) >= need ? end : i - 1;
    }

    // ---- spill ---------------------------------------------------------------------------------

    /// The forensic copy of a truncated run: raw bytes, unsanitised, best effort. `path` and
    /// `failure` are mutually exclusive.
    record Spill(Path path, String failure) {

        static final Spill NONE = new Spill(null, null);

        boolean saved() { return path != null; }
    }

    /// Writes `chunks` to a fresh temp file, owner-readable where the filesystem supports it, with
    /// `deleteOnExit` as the backstop pi never had. Any failure is reported, never thrown: the
    /// in-memory tail is the answer and the file was only ever an optimisation.
    static Spill spill(List<byte[]> chunks) { return spill(null, chunks); }

    static Spill spill(Path directory, List<byte[]> chunks) {
        Objects.requireNonNull(chunks, "chunks");
        Path file = null;
        try {
            file = createTempFile(directory);
            try (OutputStream out = Files.newOutputStream(file)) {
                for (byte[] chunk : chunks) out.write(chunk);
            }
            file.toFile().deleteOnExit();
            return new Spill(file, null);
        } catch (IOException | RuntimeException e) {
            if (file != null) {
                try { Files.deleteIfExists(file); } catch (IOException _) { /* nothing left to do */ }
            }
            return new Spill(null, reason(e));
        }
    }

    private static Path createTempFile(Path directory) throws IOException {
        // Command output can contain secrets; where POSIX bits exist the file is rw------- from birth.
        try {
            FileAttribute<?> ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
            return directory == null ? Files.createTempFile("agent-bash-", ".log", ownerOnly)
                                     : Files.createTempFile(directory, "agent-bash-", ".log", ownerOnly);
        } catch (UnsupportedOperationException _) {
            return directory == null ? Files.createTempFile("agent-bash-", ".log")
                                     : Files.createTempFile(directory, "agent-bash-", ".log");
        }
    }

    private static String reason(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
