package sdk.agent.tools.fs;

import java.util.Locale;

/// The error lines shared by the file tools — one taxonomy, always echoing the path **as the model
/// wrote it** — and the one formatter they go through: `Locale.ROOT`, so a German or Arabic host
/// locale cannot reach a notice, and hard-coded `\n` because the separators belong to the string.
final class FsMessages {

    static final String FILE_NOT_FOUND    = "File not found: %s";
    static final String NOT_A_FILE        = "Not a file: %s";
    static final String PERMISSION_DENIED = "Permission denied: %s";
    static final String NOT_WRITABLE      = "File is not writable: %s";
    static final String NOT_VALID_UTF8    = "%s is not valid UTF-8; it cannot be edited safely.";

    static final String BOM = "﻿";

    private FsMessages() { }

    static String fmt(String format, Object... args) { return String.format(Locale.ROOT, format, args); }
}
