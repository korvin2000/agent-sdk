package sdk.agent.tools.fs;

import sdk.agent.json.Doc;

/// `write`'s parameters. `content` is a `String` and the binder is strict: nanocoder types it
/// `unknown` and coerces, so a model sending an object gets it JSON-stringified into a `.ts` file
/// and reported as success.
public record WriteParams(
        @Doc("Path to the file to write (relative or absolute)") String path,
        @Doc("Content to write to the file")                     String content) { }
