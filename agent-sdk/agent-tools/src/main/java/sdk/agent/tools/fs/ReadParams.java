package sdk.agent.tools.fs;

import java.util.Optional;

import sdk.agent.json.Constraint;
import sdk.agent.json.Doc;

/// `read`'s parameters. `offset` and `limit` are declared `{"type":"integer","minimum":1}` and are
/// **actually enforced** by the structural validator, so `limit: 0` is rejected as
/// `INVALID_ARGUMENTS` before `execute` runs — pi declares a plain number there and `limit: 0`
/// produces an infinite paging loop at the same offset with empty content.
public record ReadParams(
        @Doc("Path to the file to read (relative or absolute)")            String path,
        @Doc("Line number to start reading from (1-indexed)")
        @Constraint(min = 1)                                               Optional<Integer> offset,
        @Doc("Maximum number of lines to read")
        @Constraint(min = 1)                                               Optional<Integer> limit) { }
