package sdk.agent.tools;

import java.nio.charset.CharacterCodingException;
import java.util.List;
import java.util.Objects;

import sdk.agent.json.Doc;
import sdk.agent.json.Json;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ParamCodec;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolKind;
import sdk.agent.tool.ToolResult;

/// Creates or replaces a UTF-8 file only after the workspace observation check succeeds.
public final class WriteTool implements Tool<WriteTool.Args> {

    private static final ParamCodec<Args> PARAMS = ParamCodec.ofRecord(Args.class);
    private final ToolEnvironment environment;

    public WriteTool(ToolEnvironment environment) { this.environment = Objects.requireNonNull(environment); }

    @Override public String name() { return "write"; }
    @Override public String description() { return "Create or replace a UTF-8 file in the workspace."; }
    @Override public ParamCodec<Args> params() { return PARAMS; }
    @Override public ToolKind kind() { return ToolKind.MUTATING; }
    @Override public List<String> promptGuidelines() { return List.of("Use write for new files or complete rewrites after reading existing files."); }

    @Override public ToolResult execute(ToolInvocation<Args> call) throws Exception {
        Args args = call.params();
        final long length;
        try {
            length = FileAccess.utf8Length(args.content());
        } catch (CharacterCodingException e) {
            return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, "content contains malformed UTF-16");
        }
        if (length > FileAccess.MAX_BYTES) return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, "content exceeds 8 MiB UTF-8 limit");
        final byte[] bytes;
        try {
            bytes = FileAccess.encode(args.content());
        } catch (CharacterCodingException e) {
            return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, "content contains malformed UTF-16");
        }
        FileAccess.ReplaceResult result = environment.files.replace(args.path(), bytes);
        if (result.failure() != null) return ToolResult.error(result.failure().kind(), result.failure().message());
        return ToolResult.text("Wrote " + result.byteCount() + " bytes to " + args.path() + ".",
                Json.obj("path", Json.str(args.path()), "bytes", Json.num(result.byteCount())));
    }

    @Doc("Path and exact UTF-8 contents to write.")
    public record Args(@Doc("File path") String path, @Doc("Exact file contents") String content) {
        public Args {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(content, "content");
        }
    }
}
