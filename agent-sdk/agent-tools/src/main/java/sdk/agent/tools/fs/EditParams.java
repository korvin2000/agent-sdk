package sdk.agent.tools.fs;

import java.util.List;

import sdk.agent.json.Constraint;
import sdk.agent.json.Doc;

/// `edit`'s parameters. pi's multi-edit schema, with the three `@Doc` strings verbatim from
/// `edit.ts:24-44`.
///
/// **There is no `expectedVersion` parameter.** The compare-and-swap is real, but the token lives
/// in the session, not in the schema: making the model carry a version string adds a parameter it
/// will get wrong, and a wrong token turns a correct edit into a stale-file refusal.
public record EditParams(
        @Doc("Path to the file to edit (relative or absolute)") String path,
        @Doc("""
             One or more targeted replacements. Each edit is matched against the original file, \
             not incrementally. Do not include overlapping or nested edits. If two changes touch \
             the same block or nearby lines, merge them into one edit instead.""")
        @Constraint(minItems = 1)
        List<Edit> edits) {

    public record Edit(
            @Doc("""
                 Exact text for one targeted replacement. It must be unique in the original file \
                 and must not overlap with any other edits[].oldText in the same call.""")
            String oldText,
            @Doc("Replacement text for this targeted edit.") String newText) { }
}
