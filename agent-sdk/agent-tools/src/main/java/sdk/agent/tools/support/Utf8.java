package sdk.agent.tools.support;

import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/// UTF-8 arithmetic without allocating: byte lengths from code points, and byte-bounded cuts that
/// never split a multi-byte sequence (`(b & 0xC0) == 0x80` marks a continuation byte).
public final class Utf8 {

    private Utf8() { }

    /// `s.getBytes(UTF_8).length`, without the array.
    public static long length(CharSequence s) {
        long n = 0;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) n += 1;
            else if (c < 0x800) n += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) { n += 4; i++; }
            else n += 3;
        }
        return n;
    }

    /// The last `maxBytes` bytes of `s`, cut forward to a character boundary.
    public static String lastBytes(String s, int maxBytes) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) return s;
        int start = b.length - maxBytes;
        while (start < b.length && (b[start] & 0xC0) == 0x80) start++;
        return new String(b, start, b.length - start, StandardCharsets.UTF_8);
    }

    /// Reading is non-destructive: a bad byte becomes U+FFFD and the read still succeeds.
    public static CharsetDecoder lenientDecoder() {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    /// Editing is a round trip: a file that is not valid UTF-8 is refused, never re-encoded.
    public static CharsetDecoder strictDecoder() {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }
}
