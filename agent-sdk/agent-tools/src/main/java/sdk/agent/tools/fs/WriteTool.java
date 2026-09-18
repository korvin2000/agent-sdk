package sdk.agent.tools.fs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static sdk.agent.tools.fs.FsMessages.fmt;

import java.io.IOException;
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
import sdk.agent.tools.support.Utf8;

/// `write` — create or completely replace one file, atomically, with the existing file's BOM and
/// line ending adopted rather than churned. The byte count it reports is UTF-8 bytes actually
/// written, not `String.length()`.
public final class WriteTool implements Tool<WriteParams> {

    private static final String DESCRIPTION = """
            Write content to a file. Creates the file if it doesn't exist, overwrites if it does. \
            Automatically creates parent directories. Use for new files, complete rewrites, or \
            changes affecting most of the file. For small targeted edits, use edit instead.""";

    /// The lost-update guard: has this session seen the bytes about to be destroyed?
    public static final String REFUSAL = """
            "%s" already exists and you have not read it this session. Call read on it first — if the \
            file is over 300 lines, use offset and end at the region you intend to change, not just the \
            head — so you don't discard existing content, then retry. For small changes, prefer edit over \
            a full overwrite.""";

    private static final String SUCCESS = "Successfully wrote %d bytes to %s";

    private final ToolEnvironment env;
    private final ParamCodec<WriteParams> params = ParamCodec.ofRecord(WriteParams.class);

    public WriteTool(ToolEnvironment env) { this.env = Objects.requireNonNull(env, "env"); }

    @Override public String name()                    { return "write"; }
    @Override public String description()             { return DESCRIPTION; }
    @Override public ParamCodec<WriteParams> params() { return params; }

    @Override public List<String> promptGuidelines() {
        return List.of("Use write only for new files or complete rewrites.");
    }

    @Override public Json prepareArguments(Json raw) {
        return raw instanceof Json.Obj o && !o.has("path") && o.has("file_path")
                ? o.with("path", o.members().get("file_path")).without("file_path")
                : raw;
    }

    @Override public ToolResult execute(ToolInvocation<WriteParams> call) throws IOException {
        String asWritten = call.params().path();
        Path p = env.paths().resolveForWrite(asWritten);
        if (Files.isDirectory(p)) return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, fmt(FsMessages.NOT_A_FILE, asWritten));
        boolean exists = Files.exists(p);
        if (exists && !Files.isWritable(p)) return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, fmt(FsMessages.NOT_WRITABLE, asWritten));
        if (exists && env.readBeforeOverwrite() && !env.versions().seen(p)) throw ToolException.invalid(REFUSAL.formatted(asWritten));

        byte[] bytes = adopt(call.params().content(), exists ? p : null, asWritten);
        env.paths().createParentsWithin(p);                        // NOT an unconditional mkdir -p
        env.versions().write(p, bytes);
        return ToolResult.text(fmt(SUCCESS, bytes.length, asWritten));
    }

    /// Adopt the existing file's BOM and line ending; a new file gets LF and no BOM. The model's
    /// own BOM and line endings are stripped first, so an overwrite can never double a BOM and a
    /// whole CRLF file is not churned in git. Adopting from a file that cannot be decoded is
    /// guessing, so that is refused; creating a new file decodes nothing and is never refused.
    private static byte[] adopt(String content, Path existing, String asWritten) throws IOException {
        String bom = "";
        String ending = "\n";
        if (existing != null) {
            String text;
            try {
                text = Utf8.strictDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(existing))).toString();
            } catch (CharacterCodingException _) {
                throw ToolException.invalid(fmt(FsMessages.NOT_VALID_UTF8, asWritten));
            }
            if (text.startsWith(FsMessages.BOM)) {
                bom = FsMessages.BOM;
                text = text.substring(1);
            }
            ending = EditEngine.detectLineEnding(text);
        }
        String body = EditEngine.toLf(content.startsWith(FsMessages.BOM) ? content.substring(1) : content);
        return (bom + EditEngine.restoreLineEndings(body, ending)).getBytes(UTF_8);
    }
}
