package sdk.agent.tools.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sdk.agent.tool.ErrorKind;

class PathPolicyTest {

    @Test
    void resolvesInsideTheRootAndRefusesEscapes(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.txt"), "x");
        var policy = new PathPolicy(root);

        assertEquals(root.toRealPath().resolve("a.txt"), policy.resolve("a.txt"));
        assertEquals(root.toRealPath().resolve("new/dir/file.txt"), policy.resolve("new/dir/file.txt"));
        assertEquals(root.toRealPath().resolve("a.txt"), policy.resolve("@a.txt"));
        assertEquals(root.toRealPath().resolve("a.txt"), policy.resolve(root.resolve("sub/../a.txt").toString()));

        ToolException e = assertThrows(ToolException.class, () -> policy.resolve("../outside.txt"));
        assertEquals("Path is outside the workspace root: ../outside.txt", e.getMessage());
        assertEquals(ErrorKind.INVALID_ARGUMENTS, e.kind());
        assertThrows(ToolException.class, () -> policy.resolve(root.getParent().resolve("sibling").toString()));
        assertThrows(ToolException.class, () -> policy.resolve("missing/../../escape.txt"));
    }

    @Test
    void writeDenySetAndParentCreation(@TempDir Path root) throws Exception {
        var policy = new PathPolicy(root);
        Files.createDirectories(root.resolve(".git/hooks"));
        assertThrows(ToolException.class, () -> policy.resolveForWrite(".git/hooks/pre-commit"));
        Path target = policy.resolveForWrite("src/main/App.java");
        policy.createParentsWithin(target);
        assertTrue(Files.isDirectory(target.getParent()));
        policy.createParentsWithin(target);                                  // idempotent
    }

    @Test
    void expandHandlesTildeAndUnicodeSpaces() {
        assertEquals(System.getProperty("user.home"), PathPolicy.expand("~"));
        assertEquals(System.getProperty("user.home") + "/x", PathPolicy.expand("~/x"));
        assertEquals("a b", PathPolicy.expand("a b"));
        assertEquals("~user/x", PathPolicy.expand("~user/x"));
    }

    @Test
    void reportsAMissingRoot(@TempDir Path root) {
        var policy = new PathPolicy(root.resolve("gone"));
        assertThrows(IOException.class, () -> policy.resolve("a"));
    }
}
