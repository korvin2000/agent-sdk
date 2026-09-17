package sdk.agent.tool;

import java.util.List;

import sdk.agent.json.Json;

/// The tool contract. One renderer-free interface: the host attaches UI via a side table keyed by
/// [#name]. A tool may **throw** on failure (the funnel converts it to an error result at one place,
/// so an author cannot forget) **or** return [ToolResult.Err] deliberately to keep `details` on the
/// failure path.
///
/// `description` reaches the model through the tool schema; [#promptGuidelines] are listed under
/// the system prompt's `# Tool usage` heading, so the prompt never names an unregistered tool.
///
/// @param <P> the bound parameter type; [ParamCodec] owns both its schema and its binder so the two cannot drift
public interface Tool<P> {

    /// Model-facing, already namespaced, `[A-Za-z0-9_-]{1,64}` (see [ToolNaming]).
    String name();

    String description();

    ParamCodec<P> params();

    ToolResult execute(ToolInvocation<P> call) throws Exception;

    /// Fail-safe default: forgetting to declare `READ_ONLY` costs concurrency, not correctness.
    default ToolKind kind() { return ToolKind.MUTATING; }

    /// One-line usage rules for `# Tool usage`, e.g. `Use read to examine files instead of cat or sed.`
    default List<String> promptGuidelines() { return List.of(); }

    /// Compatibility shim applied to RAW arguments before schema validation (aliases such as
    /// `file_path` → `path`, or lifting a flat `{oldText,newText}` into `edits[]`).
    default Json prepareArguments(Json raw) { return raw; }
}
