/// The coding-agent SDK core. Zero dependencies, zero OS surface: no filesystem, no processes,
/// no network — those live in `sdk.agent.tools` and in provider modules.
module sdk.agent.core {
    exports sdk.agent;
    exports sdk.agent.concurrent;
    exports sdk.agent.event;
    exports sdk.agent.hook;
    exports sdk.agent.json;
    exports sdk.agent.message;
    exports sdk.agent.prompt;
    exports sdk.agent.provider;
    exports sdk.agent.spi;
    exports sdk.agent.tool;
    exports sdk.agent.turn;

    uses sdk.agent.spi.Extension;
}
