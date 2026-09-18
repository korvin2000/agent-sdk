/// Basic workspace coding tools and per-run project instructions.
module sdk.agent.tools {
    requires transitive sdk.agent.core;

    exports sdk.agent.tools;

    provides sdk.agent.spi.Extension with sdk.agent.tools.CodingToolsExtension;
}
