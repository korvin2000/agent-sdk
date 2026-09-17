package sdk.agent.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sdk.agent.tool.ToolNaming;

class McpNamingTest {

    @Test
    void sanitisesServerNamesToTheAllowedAlphabet() {
        assertEquals("git_hub", McpNaming.sanitizeServer("git hub"));
        assertEquals("my_server", McpNaming.sanitizeServer("my.server"));
        assertEquals("a-b_c", McpNaming.sanitizeServer("a-b/c"));
        assertEquals("already_ok-1", McpNaming.sanitizeServer("already_ok-1"));
    }

    @Test
    void rejectsAServerNameThatSanitisesToNothing() {
        assertThrows(IllegalArgumentException.class, () -> McpNaming.sanitizeServer(""));
        assertThrows(IllegalArgumentException.class, () -> McpNaming.sanitizeServer(null));
    }

    /// Every illegal character becomes `_`, so a name of only illegal characters sanitises to a
    /// legal — if useless — name rather than to nothing. Two such names collide, and that is
    /// caught where every other collision is: at pool registration.
    @Test
    void anAllIllegalNameSanitisesToUnderscoresNotToEmpty() {
        assertEquals("___", McpNaming.sanitizeServer("   "));
        assertEquals("___", McpNaming.sanitizeServer("!!!"));
    }

    @Test
    void composesTheDoubleUnderscoreSpelling() {
        assertEquals("mcp__github__search", McpNaming.compose("github", "search"));
        // An underscore in either half is exactly why the composed name is never reverse-parsed.
        assertEquals("mcp__my_server__list_files", McpNaming.compose("my_server", "list_files"));
    }

    @Test
    void delegatesTheLengthRuleToCoreRatherThanRestatingIt() {
        String tool = "t".repeat(80);
        String composed = McpNaming.compose("srv", tool);

        assertEquals(ToolNaming.compose("mcp__srv__" + tool), composed);
        assertEquals(ToolNaming.MAX_LENGTH, composed.length());
        assertTrue(ToolNaming.isValid(composed));
        assertEquals(composed, McpNaming.compose("srv", tool));            // deterministic across restarts
    }

    @Test
    void sanitisationInsideEitherHalfCanProduceACollision() {
        // The only two sources of a collision are sanitisation and truncation; this is the first.
        assertEquals(McpNaming.compose("s", "a b"), McpNaming.compose("s", "a.b"));
        assertNotEquals(McpNaming.compose("s", "a"), McpNaming.compose("s", "b"));
    }
}
