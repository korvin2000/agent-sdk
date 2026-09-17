/// Test doubles for hosts, extensions and the engine's own suite: a scripted provider, an
/// invariant-asserting sink, a controllable clock and a configurable tool. Depends on the core
/// only — never on a test framework, so a host can use it from any harness.
module sdk.agent.testkit {
    requires transitive sdk.agent.core;

    exports sdk.agent.testkit;
}
