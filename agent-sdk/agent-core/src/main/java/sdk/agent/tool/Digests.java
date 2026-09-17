package sdk.agent.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/// SHA-256 as lower-case hex, the one digest the SDK uses (tool-set hash, call signatures, name
/// truncation, file versions).
public final class Digests {

    private Digests() { }

    public static String sha256Hex(String text) { return sha256Hex(text.getBytes(StandardCharsets.UTF_8)); }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }
}
