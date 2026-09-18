package sdk.agent.tools;

import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import sdk.agent.json.Constraint;
import sdk.agent.json.Doc;
import sdk.agent.json.Json;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ParamCodec;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolInvocation;
import sdk.agent.tool.ToolKind;
import sdk.agent.tool.ToolResult;

/// Applies exact, non-overlapping replacements against one observed original file.
public final class EditTool implements Tool<EditTool.Args> {

    private static final int MODEL_EXCERPT_LIMIT = 64 * 1024;
    private static final ParamCodec<Args> PARAMS = ParamCodec.ofRecord(Args.class);
    private final ToolEnvironment environment;

    public EditTool(ToolEnvironment environment) { this.environment = Objects.requireNonNull(environment); }

    @Override public String name() { return "edit"; }
    @Override public String description() { return "Apply exact unique text replacements to an observed UTF-8 file."; }
    @Override public ParamCodec<Args> params() { return PARAMS; }
    @Override public ToolKind kind() { return ToolKind.MUTATING; }
    @Override public List<String> promptGuidelines() { return List.of("Use edit for precise changes; each oldText must match exactly once."); }

    @Override public ToolResult execute(ToolInvocation<Args> call) throws Exception {
        Args args = call.params();
        synchronized (environment.files) {
            FileAccess.ReadResult read = environment.files.readForEdit(args.path());
            if (read.failure() != null) return ToolResult.error(read.failure().kind(), read.failure().message());
            if (read.data().mimeType() != null) return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, "images cannot be edited as text");
            String original = read.data().text();
            var matches = new ArrayList<Match>(args.edits().size());
            long resultBytes = read.data().bytes().length;
            for (Replacement edit : args.edits()) {
                if (edit.oldText().isEmpty()) return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, "oldText must be nonempty");
                final long replacementBytes;
                final long oldBytes;
                try {
                    replacementBytes = FileAccess.utf8Length(edit.newText());
                    oldBytes = FileAccess.utf8Length(edit.oldText());
                } catch (CharacterCodingException e) {
                    return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, "replacement text contains malformed UTF-16");
                }
                int start = original.indexOf(edit.oldText());
                if (start < 0) return ToolResult.error(ErrorKind.BLOCKED, "oldText was not found; reread and retry");
                if (original.indexOf(edit.oldText(), start + 1) >= 0) {
                    return ToolResult.error(ErrorKind.BLOCKED, "oldText must occur exactly once: " + edit.oldText());
                }
                matches.add(new Match(start, start + edit.oldText().length(), edit.newText()));
                resultBytes += replacementBytes - oldBytes;
            }
            matches.sort(Comparator.comparingInt(Match::start));
            for (int i = 1; i < matches.size(); i++) {
                if (matches.get(i - 1).end() > matches.get(i).start()) {
                    return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, "replacement ranges overlap");
                }
            }
            if (resultBytes < 0 || resultBytes > FileAccess.MAX_BYTES) {
                return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, "edited file exceeds 8 MiB UTF-8 limit");
            }
            StringBuilder updated = new StringBuilder(original);
            for (int i = matches.size() - 1; i >= 0; i--) {
                Match match = matches.get(i);
                updated.replace(match.start(), match.end(), match.newText());
            }
            final byte[] bytes;
            try {
                bytes = FileAccess.encode(updated.toString());
            } catch (CharacterCodingException e) {
                return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, "replacement text contains malformed UTF-16");
            }
            if (bytes.length > FileAccess.MAX_BYTES) return ToolResult.error(ErrorKind.INVALID_ARGUMENTS, "edited file exceeds 8 MiB UTF-8 limit");
            FileAccess.ReplaceResult write = environment.files.replace(args.path(), bytes);
            if (write.failure() != null) return ToolResult.error(write.failure().kind(), write.failure().message());

            int regionStart = Integer.MAX_VALUE, regionEnd = 0, delta = 0;
            for (Match match : matches) {
                int start = match.start() + delta;
                int end = start + match.newText().length();
                regionStart = Math.min(regionStart, start);
                regionEnd = Math.max(regionEnd, end);
                delta += match.newText().length() - (match.end() - match.start());
            }
            String excerpt = matches.isEmpty() ? "" : excerpt(updated.toString(), regionStart, regionEnd);
            int excerptFrom = Math.max(0, regionStart - 4096);
            int excerptTo = Math.min(updated.length(), excerptFrom + MODEL_EXCERPT_LIMIT);
            boolean truncated = !matches.isEmpty() && (regionEnd > excerptTo || excerpt.length() < excerptTo - excerptFrom);
            String message = "Edited " + args.path() + " (" + matches.size() + " replacement" + (matches.size() == 1 ? "" : "s") + ").\n" + excerpt;
            return ToolResult.text(message, Json.obj("path", Json.str(args.path()), "edits", Json.num(matches.size()),
                    "bytes", Json.num(bytes.length), "truncated", Json.bool(truncated)));
        }
    }

    private static String excerpt(String text, int start, int end) {
        if (end <= start) return "";
        int from = Math.max(0, start - 4096);
        if (from > 0 && Character.isLowSurrogate(text.charAt(from))) from--;
        int to = Math.min(text.length(), from + MODEL_EXCERPT_LIMIT);
        return FileAccess.utf8Prefix(text.substring(from, to), MODEL_EXCERPT_LIMIT);
    }

    private record Match(int start, int end, String newText) { }

    @Doc("Path and exact replacements to apply against the original file.")
    public record Args(@Doc("File path") String path,
                       @Doc("One or more exact replacements") @Constraint(minItems=1) List<Replacement> edits) {
        public Args {
            Objects.requireNonNull(path, "path");
            edits = List.copyOf(Objects.requireNonNull(edits, "edits"));
        }
    }

    public record Replacement(@Doc("Text that must occur exactly once") String oldText,
                              @Doc("Exact replacement text") String newText) {
        public Replacement {
            Objects.requireNonNull(oldText, "oldText");
            Objects.requireNonNull(newText, "newText");
        }
    }
}
