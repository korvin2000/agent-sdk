package sdk.agent.tools.prompts;

/// kon's `escape_xml`, so file contents and descriptions cannot close the prompt's tags early.
final class Xml {

    private Xml() { }

    static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
