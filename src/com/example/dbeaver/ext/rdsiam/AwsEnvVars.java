/*
 * DBeaver AWS RDS IAM Authentication extension.
 */
package com.example.dbeaver.ext.rdsiam;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a pasted block of shell "environment variable" assignments and extracts
 * AWS credential values. Supports the common formats AWS emits from the console
 * / SSO "copy credentials" widgets across platforms:
 *
 * <pre>
 *   export AWS_ACCESS_KEY_ID="ASIA..."        # bash / zsh (macOS, Linux)
 *   set AWS_ACCESS_KEY_ID=ASIA...             # Windows cmd
 *   setx AWS_ACCESS_KEY_ID "ASIA..."          # Windows cmd (persistent)
 *   $env:AWS_ACCESS_KEY_ID="ASIA..."          # Windows PowerShell
 *   AWS_ACCESS_KEY_ID=ASIA...                 # plain KEY=VALUE
 * </pre>
 *
 * Values may be unquoted, double-quoted or single-quoted, with optional
 * surrounding whitespace and an optional trailing semicolon. Pure JDK, no
 * external dependencies.
 */
public final class AwsEnvVars {

    // Optional prefix (export / set / setx / $env:), NAME, '=', then value to EOL.
    private static final Pattern ASSIGN = Pattern.compile(
        "(?im)^[ \\t]*(?:export[ \\t]+|set[ \\t]+|setx[ \\t]+|\\$env:[ \\t]*)?(AWS_[A-Z0-9_]+)[ \\t]*=[ \\t]*(.*)$");

    // Windows "setx NAME value" uses a space, not '='.
    private static final Pattern SETX = Pattern.compile(
        "(?im)^[ \\t]*setx[ \\t]+(AWS_[A-Z0-9_]+)[ \\t]+(.*)$");

    private AwsEnvVars() {
    }

    /**
     * @return an ordered map of AWS_* variable name (upper-case) to cleaned value.
     *         Empty if nothing recognizable was found.
     */
    public static Map<String, String> parse(String input) {
        Map<String, String> out = new LinkedHashMap<>();
        if (input == null || input.isEmpty()) {
            return out;
        }
        Matcher m = ASSIGN.matcher(input);
        while (m.find()) {
            put(out, m.group(1), m.group(2));
        }
        Matcher s = SETX.matcher(input);
        while (s.find()) {
            // Do not override a value already found via KEY=VALUE form.
            String name = s.group(1).toUpperCase(Locale.ROOT);
            if (!out.containsKey(name)) {
                put(out, s.group(1), s.group(2));
            }
        }
        return out;
    }

    /**
     * True if the text looks like a credentials block worth auto-filling from
     * (contains at least one recognized AWS credential variable).
     */
    public static boolean looksLikeCredentialsBlock(String input) {
        Map<String, String> vars = parse(input);
        return vars.containsKey("AWS_ACCESS_KEY_ID")
            || vars.containsKey("AWS_SECRET_ACCESS_KEY")
            || vars.containsKey("AWS_SESSION_TOKEN")
            || vars.containsKey("AWS_SECURITY_TOKEN");
    }

    /** Region from either AWS_REGION or AWS_DEFAULT_REGION, or null. */
    public static String region(Map<String, String> vars) {
        String r = vars.get("AWS_REGION");
        return r != null ? r : vars.get("AWS_DEFAULT_REGION");
    }

    /** Session token from either AWS_SESSION_TOKEN or AWS_SECURITY_TOKEN, or null. */
    public static String sessionToken(Map<String, String> vars) {
        String t = vars.get("AWS_SESSION_TOKEN");
        return t != null ? t : vars.get("AWS_SECURITY_TOKEN");
    }

    private static void put(Map<String, String> out, String rawName, String rawValue) {
        String value = clean(rawValue);
        if (value != null && !value.isEmpty()) {
            out.put(rawName.toUpperCase(Locale.ROOT), value);
        }
    }

    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.endsWith(";")) {
            v = v.substring(0, v.length() - 1).trim();
        }
        if (v.length() >= 2
            && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1);
        }
        return v.trim();
    }
}
