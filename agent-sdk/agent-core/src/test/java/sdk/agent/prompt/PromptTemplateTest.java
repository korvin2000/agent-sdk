package sdk.agent.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PromptTemplateTest {

    @Test void rendersPlaceholdersAndLeavesValuesAlone() {
        var template = new PromptTemplate("Hi ${name}, ${greeting} ${name}");
        assertEquals("Hi Ann, $1 ${x} Ann", template.render(Map.of("name", "Ann", "greeting", "$1 ${x}")));
        assertEquals("plain", new PromptTemplate("plain").render(Map.of()));
    }

    @Test void aMissingValueFailsInsteadOfReachingTheModel() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> new PromptTemplate("${gone}").render(Map.of()));
        assertTrue(thrown.getMessage().contains("${gone}"));
    }

    @Test void readsAndStripsAResourceAndNamesAMissingOne() {
        var in = new ByteArrayInputStream("\n  # Title\n\ntext — ok\n\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("# Title\n\ntext — ok", PromptTemplate.read(in, "t.md").text());
        var thrown = assertThrows(IllegalStateException.class, () -> PromptTemplate.read(null, "missing.md"));
        assertTrue(thrown.getMessage().contains("missing.md"));
    }
}
