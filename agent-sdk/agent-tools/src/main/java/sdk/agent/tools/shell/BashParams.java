package sdk.agent.tools.shell;

import java.util.Optional;

import sdk.agent.json.Doc;

/// `bash`'s parameters. `timeout` is optional because the environment's default is the honest
/// answer for almost every call; the description renders that default so the two cannot drift.
public record BashParams(
        @Doc("Bash command to execute")                             String command,
        @Doc("Timeout in seconds (optional)")                       Optional<Integer> timeout) {

    public BashParams {
        timeout = timeout == null ? Optional.empty() : timeout;
    }
}
