package sdk.agent.tools.fs;

import static sdk.agent.tools.fs.FsMessages.fmt;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ParamCodec;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolKind;
import sdk.agent.tool.ToolResult;
import sdk.agent.tools.ToolEnvironment;
import sdk.agent.tools.support.Truncation;
import sdk.agent.tools.support.Utf8;

/// `read` — 1-indexed, half-open paging over a text file, in **one streaming pass to EOF**. Only
/// the requested window is materialised, but every line is still counted, so a notice reports the
/// *file's* totals and never the retained window's. Decoding is lenient: a stray byte becomes
/// U+FFFD and `details` carries `partialDecode`, because reading is non-destructive and a model
/// that can see 99 % of a file can still work. An image (png, jpeg, gif, webp — sniffed by magic
/// number, never by extension) is returned as one image block.
public final class ReadTool implements Tool<ReadParams> {

    private static final String DESCRIPTION = """
            Read the contents of a file. Supports text files and images (png, jpeg, gif, webp). \
            Output is truncated to %d lines or %dKB (whichever is hit first). Use offset/limit for \
            large files. When you need the full file, continue with offset until complete. %s""";

    private static final String NUMBERS_OFF =
            "Line numbers are not included — copy text from it verbatim when you need an exact match for `edit`.";
    private static final String NUMBERS_ON =
            "Each line is prefixed with its 1-based number and a tab; never include that prefix in an `edit` oldText.";

    private static final String SHOWING_LINES =
            "\n\n[Showing lines %d-%d of %d. Use offset=%d to continue.]";
    private static final String SHOWING_LINES_BYTES =
            "\n\n[Showing lines %d-%d of %d (%s limit). Use offset=%d to continue.]";
    private static final String MORE_LINES =
            "\n\n[%d more lines in file. Use offset=%d to continue.]";
    private static final String LINE_TOO_LONG =
            "[Line %d is %s, exceeds %s limit. Retry with a smaller limit, or use bash with a byte-bounded command.]";

    /// The inline-image ceiling the model APIs share.
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final String IMAGE_TOO_LARGE = "Image %s is %s, exceeds the %s limit for inline images.";

    private final ToolEnvironment env;
    private final ParamCodec<ReadParams> params = ParamCodec.ofRecord(ReadParams.class);

    public ReadTool(ToolEnvironment env) { this.env = Objects.requireNonNull(env, "env"); }

    @Override public String name()                 { return "read"; }
    @Override public ParamCodec<ReadParams> params() { return params; }
    @Override public ToolKind kind()               { return ToolKind.READ_ONLY; }

    @Override public String description() {
        return fmt(DESCRIPTION, Truncation.DEFAULT_MAX_LINES, Truncation.DEFAULT_MAX_BYTES / 1024,
                   env.lineNumbers() ? NUMBERS_ON : NUMBERS_OFF);
    }

    @Override public List<String> promptGuidelines() {
        return List.of("Use read to examine files instead of cat or sed.");
    }

    /// `file_path` is the name half the corpus uses; renaming it here costs one turn less than a
    /// `must NOT have additional properties` rejection.
    @Override public Json prepareArguments(Json raw) {
        return raw instanceof Json.Obj o && !o.has("path") && o.has("file_path")
                ? o.with("path", o.members().get("file_path")).without("file_path")
                : raw;
    }

    @Override public ToolResult execute(ToolInvocation<ReadParams> call) throws IOException {
        String asWritten = call.params().path();
        Path p = env.paths().resolve(asWritten);
        if (Files.isDirectory(p)) return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, fmt(FsMessages.NOT_A_FILE, asWritten));

        try (var in = new BufferedInputStream(Files.newInputStream(p))) {
            String mime = imageType(in);
            return mime != null
                    ? image(in, p, asWritten, mime)
                    : text(in, p, call.params().offset().orElse(1), call.params().limit().orElse(Integer.MAX_VALUE));
        } catch (NoSuchFileException _) {
            return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, fmt(FsMessages.FILE_NOT_FOUND, asWritten));
        } catch (AccessDeniedException _) {
            return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, fmt(FsMessages.PERMISSION_DENIED, asWritten));
        }
    }

    /// One pass to EOF: only the window is materialised, every line is counted, every byte is hashed.
    private ToolResult text(InputStream in, Path p, int offset, int limit) throws IOException {
        var sb = new StringBuilder();
        int total = 0;
        int kept = 0;
        long totalBytes = 0;
        long firstKeptBytes = 0;
        boolean partialDecode = false;
        MessageDigest digest = sha256();                                    // hashed while streaming: arms edit's compare-and-swap
        try (var r = new BufferedReader(new InputStreamReader(new DigestInputStream(in, digest), Utf8.lenientDecoder()))) {
            for (String line; (line = r.readLine()) != null; ) {
                total++;
                totalBytes += Utf8.length(line) + 1;
                partialDecode |= line.indexOf('�') >= 0;                // the decoder substitutes silently
                if (total < offset || kept == limit) continue;              // count on, append no more
                String rendered = env.lineNumbers() ? total + "\t" + line : line;
                if (kept == 0) firstKeptBytes = Utf8.length(rendered);
                else sb.append('\n');
                sb.append(rendered);
                kept++;
            }
        }

        if (offset > Math.max(total, 1)) {
            return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, fmt("Offset %d is beyond end of file (%d lines total)", offset, total));
        }

        var t = Truncation.head(sb.toString(), env.limits()).withSourceTotals(total, totalBytes);
        int last = offset - 1 + t.outputLines();
        // A window that showed something (or an empty file) counts as seen; a first line too long to show does not.
        if (t.outputLines() > 0 || total == 0) env.versions().markSeen(p, FileVersions.fromDigest(digest.digest()));
        return new ToolResult.Ok(List.of(ContentBlock.Text.of(render(t, offset, last, total - last, firstKeptBytes))),
                                 t.toJson().with("partialDecode", Json.bool(partialDecode)));
    }

    /// At most one notice: an oversized first line, else the truncation notice chosen by
    /// `truncatedBy`, else the "more lines" hint — whose count comes from the true total.
    private static String render(Truncation.Result t, int offset, int last, int remaining, long firstLineBytes) {
        String cap = Truncation.formatSize(t.limits().maxBytes());
        if (t.firstLineExceedsLimit()) {
            return fmt(LINE_TOO_LONG, offset, Truncation.formatSize(firstLineBytes), cap);
        }
        if (t.truncated()) {
            return t.content() + (t.truncatedBy() == Truncation.Result.By.BYTES
                    ? fmt(SHOWING_LINES_BYTES, offset, last, t.totalLines(), cap, last + 1)
                    : fmt(SHOWING_LINES, offset, last, t.totalLines(), last + 1));
        }
        return remaining > 0 ? t.content() + fmt(MORE_LINES, remaining, last + 1) : t.content();
    }

    private ToolResult image(InputStream in, Path p, String asWritten, String mime) throws IOException {
        byte[] raw = in.readNBytes(MAX_IMAGE_BYTES + 1);
        if (raw.length > MAX_IMAGE_BYTES) {
            return ToolResult.error(ErrorKind.INVALID_ARGUMENTS,
                    fmt(IMAGE_TOO_LARGE, asWritten, Truncation.formatSize(Files.size(p)), Truncation.formatSize(MAX_IMAGE_BYTES)));
        }
        env.versions().markSeen(p, FileVersions.versionOf(raw));
        return new ToolResult.Ok(List.of(new ContentBlock.Image(Base64.getEncoder().encodeToString(raw), mime)),
                                 Json.obj("mimeType", Json.str(mime), "bytes", Json.num(raw.length)));
    }

    /// The magic numbers of the four formats the model APIs accept inline; `null` for anything else.
    static String imageType(BufferedInputStream in) throws IOException {
        in.mark(12);
        byte[] h = in.readNBytes(12);
        in.reset();
        if (starts(h, 0x89, 'P', 'N', 'G')) return "image/png";
        if (starts(h, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (starts(h, 'G', 'I', 'F', '8')) return "image/gif";
        if (starts(h, 'R', 'I', 'F', 'F') && h.length == 12 && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return "image/webp";
        return null;
    }

    private static boolean starts(byte[] h, int... magic) {
        if (h.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if ((h[i] & 0xFF) != magic[i]) return false;
        return true;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }
}
