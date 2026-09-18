package sdk.agent.tools;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import sdk.agent.json.Constraint;
import sdk.agent.json.Doc;
import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ParamCodec;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolKind;
import sdk.agent.tool.ToolResult;

/// Reads bounded UTF-8 text windows or small images from the workspace.
public final class ReadTool implements Tool<ReadTool.Args> {

    private static final int MODEL_TEXT_LIMIT = 64 * 1024;
    private static final ParamCodec<Args> PARAMS = ParamCodec.ofRecord(Args.class);
    private final ToolEnvironment environment;

    public ReadTool(ToolEnvironment environment) { this.environment = Objects.requireNonNull(environment); }

    @Override public String name() { return "read"; }
    @Override public String description() { return "Read a bounded UTF-8 text window or image from the workspace."; }
    @Override public ParamCodec<Args> params() { return PARAMS; }
    @Override public ToolKind kind() { return ToolKind.READ_ONLY; }
    @Override public List<String> promptGuidelines() { return List.of("Use read to inspect files before modifying them."); }

    @Override public ToolResult execute(ToolInvocation<Args> call) throws Exception {
        Args args = call.params();
        int offset = args.offset().orElse(1);
        int limit = args.limit().orElse(2000);
        if (offset < 1 || limit < 1) return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, "offset and limit must be positive");

        synchronized (environment.files) {
            FileAccess.ReadResult result = environment.files.read(args.path());
            if (result.failure() != null) return ToolResult.error(result.failure().kind(), result.failure().message());
            FileAccess.FileData data = result.data();
            ToolResult presentation;
            if (data.mimeType() != null) {
                if (args.offset().isPresent() || args.limit().isPresent()) {
                    return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, "offset and limit are only valid for text files");
                }
                presentation = new ToolResult.Ok(
                        List.of(new ContentBlock.Image(Base64.getEncoder().encodeToString(data.bytes()), data.mimeType())),
                        JsonDetails.image(args.path(), data.bytes().length));
            } else {
                presentation = textPresentation(args.path(), data.text(), offset, limit);
            }
            // A read is observable only after the complete model-facing presentation succeeded.
            if (!presentation.isError()) environment.files.observe(data);
            return presentation;
        }
    }

    private static ToolResult textPresentation(String path, String text, int offset, int limit) {
        int first = text.startsWith("\uFEFF") ? 1 : 0;
        Window window = window(text, first, offset, limit);
        if (window == null) return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, "offset is beyond end of file");

        String selected = window.start() == 0 && window.end() == text.length()
                ? text : text.substring(window.start(), window.end());
        String prefix = FileAccess.utf8Prefix(selected, MODEL_TEXT_LIMIT);
        if (prefix.length() == selected.length()) {
            return ToolResult.text(selected, JsonDetails.text(path, window, false, false, window.nextLine(),
                    window.returnedLines()));
        }

        // Reserve a conservative marker budget so guidance itself can never exceed the model cap.
        int budget = MODEL_TEXT_LIMIT - 128;
        prefix = FileAccess.utf8Prefix(selected, budget);
        boolean partial = !prefix.endsWith("\n");
        int nextLine = offset + countNewlines(prefix);
        String marker = truncationMarker(partial, nextLine);
        int shownLines = countNewlines(prefix) + (partial && !prefix.isEmpty() ? 1 : 0);
        return ToolResult.text(prefix + marker, JsonDetails.text(path, window, true, partial, nextLine, shownLines));
    }

    private static int countNewlines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
        return count;
    }

    /** Finds line boundaries without materialising the file as a list of line strings. */
    private static Window window(String text, int first, int offset, int limit) {
        long requestedEnd = (long) offset + limit - 1L;
        int lineCount = 1;
        int start = offset == 1 ? first : -1;
        int end = -1;
        for (int i = first; i < text.length(); i++) {
            if (text.charAt(i) != '\n') continue;
            if ((long) lineCount == requestedEnd) end = i + 1;
            if (i + 1 < text.length()) {
                lineCount++;
                if (lineCount == offset) start = i + 1;
            }
        }
        if (offset > lineCount) return null;
        int available = lineCount - offset + 1;
        int selectedLines = Math.min(available, limit);
        if (end < 0 || selectedLines == available) end = text.length();
        int nextLine = selectedLines < available ? offset + selectedLines : 0;
        return new Window(lineCount, offset, limit, selectedLines, nextLine, start, end);
    }

    private static String truncationMarker(boolean partialLine, int nextLine) {
        return "\n[output truncated; reread "
                + (partialLine ? "line " + nextLine + " (this line is partial)" : "from line " + nextLine)
                + "]";
    }

    private record Window(int lineCount, int offset, int limit, int returnedLines, int nextLine, int start, int end) { }

    record JsonDetails() {
        static Json.Obj image(String path, int bytes) {
            return Json.obj("path", Json.str(path), "bytes", Json.num(bytes));
        }

        static Json.Obj text(String path, Window window, boolean truncated, boolean partialLine, int nextLine,
                             int returnedLines) {
            var details = Json.obj("path", Json.str(path), "lineCount", Json.num(window.lineCount()),
                    "offset", Json.num(window.offset()), "limit", Json.num(window.limit()),
                    "returnedLines", Json.num(returnedLines), "truncated", Json.bool(truncated),
                    "partialLine", Json.bool(partialLine));
            return nextLine == 0 ? details : details.with("nextLine", Json.num(nextLine));
        }
    }

    @Doc("Path relative to the workspace, or an absolute path inside it.")
    public record Args(@Doc("File path") String path,
                       @Doc("One-based starting line") @Constraint(min=1) Optional<Integer> offset,
                       @Doc("Maximum lines to return") @Constraint(min=1) Optional<Integer> limit) {
        public Args {
            Objects.requireNonNull(path, "path");
            offset = offset == null ? Optional.empty() : offset;
            limit = limit == null ? Optional.empty() : limit;
        }
    }
}
