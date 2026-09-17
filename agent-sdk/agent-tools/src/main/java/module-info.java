/// The four base coding tools, their support utilities and the shipped system-prompt corpus.
module sdk.agent.tools {
    requires transitive sdk.agent.core;

    exports sdk.agent.tools;
    exports sdk.agent.tools.fs;
    exports sdk.agent.tools.prompts;
    exports sdk.agent.tools.shell;
    exports sdk.agent.tools.support;

    provides sdk.agent.spi.Extension with sdk.agent.tools.CodingToolsExtension;
}
