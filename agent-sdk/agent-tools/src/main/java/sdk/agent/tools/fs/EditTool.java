package sdk.agent.tools.fs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static sdk.agent.tools.fs.FsMessages.fmt;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import sdk.agent.json.Json;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ParamCodec;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolResult;
import sdk.agent.tools.ToolEnvironment;
import sdk.agent.tools.support.ToolException;
import sdk.agent.tools.support.Truncation;
import sdk.agent.tools.support.Utf8;

/// `edit` — one file, many exact-text replacements, all or nothing, with a numbered post-edit echo
/// (capped), BOM and line endings preserved, and a session-carried compare-and-swap.
public final class EditTool implements Tool<EditParams> {

    private static final String DESCRIPTION = """
            Edit a single file using exact text replacement. Every edits[].oldText must match a \
            unique, non-overlapping region of the original file. If two changes affect the same \
            block or nearby lines, merge them into one edit instead of emitting overlapping edits. \
            Do not include large unchanged regions just to connect distant changes.""";

    private static final String SUCCESS = "Successfully replaced %d block(s) in %s.";
    private static final int CONTEXT_LINES = 20;
    private static final int MAX_RESULT_CHARS = 4000;
    private static final String PREVIEW_TRUNCATED =
            "\n... [Preview truncated: %d characters; use read with a narrow range to inspect omitted content] ...\n";

    private final ToolEnvironment env;
    private final ParamCodec<EditParams> params = ParamCodec.ofRecord(EditParams.class);

    public EditTool(ToolEnvironment env) { this.env = Objects.requireNonNull(env, "env"); }

    @Override public String name()                   { return "edit"; }
    @Override public String description()            { return DESCRIPTION; }
    @Override public ParamCodec<EditParams> params() { return params; }

    /// The first four encode the multi-edit contract; the fifth closes the hole the numbered echo opens.
    @Override public List<String> promptGuidelines() {
        return List.of(
                "Use edit for precise changes (edits[].oldText must match exactly)",
                "When changing multiple separate locations in one file, use one edit call with multiple entries in edits[] instead of multiple edit calls",
                "Each edits[].oldText is matched against the original file, not after earlier edits are applied. Do not emit overlapping or nested edits. Merge nearby changes into one edit.",
                "Keep edits[].oldText as small as possible while still being unique in the file. Do not pad with large unchanged regions.",
                "The numbered context returned after an edit is for reading only — never include those numbers in oldText.");
    }

    /// Two shims on the raw arguments: the `file_path` alias, and a flat `{oldText, newText}` pair
    /// lifted into a one-element `edits[]` — both are shapes models emit often enough that the
    /// alternative is a `must NOT have additional properties` rejection and a wasted turn.
    @Override public Json prepareArguments(Json raw) {
        if (!(raw instanceof Json.Obj o)) return raw;
        Json.Obj out = o;
        if (!out.has("path") && out.has("file_path")) {
            out = out.with("path", out.members().get("file_path")).without("file_path");
        }
        if (!out.has("edits") && out.has("oldText") && out.has("newText")) {
            out = out.with("edits", Json.arr(Json.obj("oldText", out.members().get("oldText"),
                                                      "newText", out.members().get("newText"))))
                     .without("oldText").without("newText");
        }
        return out;
    }

    @Override public ToolResult execute(ToolInvocation<EditParams> call) throws Exception {
        String asWritten = call.params().path();
        List<EditParams.Edit> edits = call.params().edits();
        Path p = env.paths().resolveForWrite(asWritten);
        if (Files.isDirectory(p)) return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, fmt(FsMessages.NOT_A_FILE, asWritten));
        if (!Files.exists(p)) return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, fmt(FsMessages.FILE_NOT_FOUND, asWritten));
        if (!Files.isWritable(p)) return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, fmt(FsMessages.NOT_WRITABLE, asWritten));

        // Hash the bytes we are about to act on, in the same operation; `recorded` is what the
        // session saw before this call. If the file moved under us since then, refuse now.
        String recorded = env.versions().recordedVersion(p);
        var snapshot = env.versions().read(p);
        if (recorded != null && !recorded.equals(snapshot.version())) throw FileVersions.stale(asWritten);

        String raw;
        try {
            raw = Utf8.strictDecoder().decode(ByteBuffer.wrap(snapshot.bytes())).toString();
        } catch (CharacterCodingException _) {
            throw ToolException.invalid(fmt(FsMessages.NOT_VALID_UTF8, asWritten));   // a lossy round trip would corrupt the file
        }
        String bom = raw.startsWith(FsMessages.BOM) ? FsMessages.BOM : "";
        String content = raw.substring(bom.length());
        String ending = EditEngine.detectLineEnding(content);

        var applied = EditEngine.apply(EditEngine.toLf(content), edits, asWritten);

        byte[] bytes = (bom + EditEngine.restoreLineEndings(applied.newContent(), ending)).getBytes(UTF_8);
        String version = env.versions().commit(p, bytes, snapshot.version(), asWritten);

        int first = applied.firstChangedLine();
        int last = EditEngine.lastChangedLine(applied.baseContent(), applied.newContent());
        String text = fmt(SUCCESS, edits.size(), asWritten) + updatedFileContext(applied.newContent(), first, last);
        return ToolResult.text(cap(text), Json.obj("edits", Json.num(edits.size()),
                                                   "version", Json.str(version),
                                                   "firstChangedLine", Json.num(first),
                                                   "bytes", Json.num(bytes.length)));
    }

    /// 20 lines either side, 4-space right-aligned numbers, `(lines X-Y of Z)` framing, explicit
    /// omission markers — it removes an entire class of redundant re-reads.
    private static String updatedFileContext(String content, int startLine, int endLine) {
        List<String> lines = Truncation.lines(content);
        int from = Math.max(1, startLine - CONTEXT_LINES);
        int to = Math.min(lines.size(), endLine + CONTEXT_LINES);

        var sb = new StringBuilder();
        sb.append(fmt("\n\nUpdated file context (lines %d-%d of %d):\n", from, to, lines.size()));
        if (from > 1) sb.append(fmt("[... lines 1-%d omitted ...]\n", from - 1));
        for (int i = from - 1; i < to; i++) sb.append(fmt("%4s: %s\n", i + 1, lines.get(i)));
        if (to < lines.size()) sb.append(fmt("[... lines %d-%d omitted ...]\n", to + 1, lines.size()));
        return sb.toString();
    }

    /// The marker's own length is subtracted from the budget before the head/tail split, so the
    /// capped result is never *longer* than the cap.
    private static String cap(String content) {
        if (content.length() <= MAX_RESULT_CHARS) return content;
        String marker = fmt(PREVIEW_TRUNCATED, content.length());
        int remaining = MAX_RESULT_CHARS - marker.length();
        int head = remaining / 2;
        int tail = remaining - head;
        return content.substring(0, head) + marker + content.substring(content.length() - tail);
    }
}
