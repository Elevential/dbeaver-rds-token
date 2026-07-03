/*
 * DBeaver AWS RDS IAM Authentication extension.
 */
package com.example.dbeaver.ext.rdsiam;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin wrapper around the locally installed AWS CLI, used to resolve credentials
 * for a selected profile at connect time.
 *
 * We shell out to {@code aws configure export-credentials} rather than bundle the
 * AWS SDK: that command resolves credentials for ANY profile type (SSO,
 * assume-role, credential_process, static keys), transparently using / refreshing
 * cached SSO tokens. Pure JDK, no external dependencies.
 */
public final class AwsCli {

    private static final long TIMEOUT_SECONDS = 60;

    private static final Pattern ACCESS_KEY = jsonString("AccessKeyId");
    private static final Pattern SECRET_KEY = jsonString("SecretAccessKey");
    private static final Pattern SESSION_TOKEN = jsonString("SessionToken");

    private AwsCli() {
    }

    /** Resolved temporary credentials for a profile. */
    public static final class Credentials {
        public final String accessKeyId;
        public final String secretAccessKey;
        public final String sessionToken;

        Credentials(String accessKeyId, String secretAccessKey, String sessionToken) {
            this.accessKeyId = accessKeyId;
            this.secretAccessKey = secretAccessKey;
            this.sessionToken = sessionToken;
        }
    }

    /** @return path to the aws executable, or null if not found. */
    public static String locate() {
        String override = System.getenv("AWS_CLI_PATH");
        if (override != null && new File(override).canExecute()) {
            return override;
        }
        List<String> candidates = new ArrayList<>();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            candidates.add("C:\\Program Files\\Amazon\\AWSCLIV2\\aws.exe");
            candidates.add("C:\\Program Files (x86)\\Amazon\\AWSCLIV2\\aws.exe");
        } else {
            candidates.add("/usr/local/bin/aws");
            candidates.add("/opt/homebrew/bin/aws");
            candidates.add("/usr/bin/aws");
            String home = System.getProperty("user.home");
            if (home != null) {
                candidates.add(home + "/.local/bin/aws");
            }
        }
        for (String c : candidates) {
            if (new File(c).canExecute()) {
                return c;
            }
        }
        // Fall back to PATH resolution (may be limited in GUI-launched apps).
        return windows ? "aws.exe" : "aws";
    }

    public static boolean isAvailable() {
        String exe = locate();
        try {
            return run(new String[]{exe, "--version"}).exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolves credentials for the given profile via
     * {@code aws configure export-credentials --profile NAME --format process}.
     *
     * @throws IOException with a user-facing message on failure (e.g. an expired
     *         SSO session that needs {@code aws sso login}).
     */
    public static Credentials exportCredentials(String profile) throws IOException {
        String exe = locate();
        Result r = run(new String[]{
            exe, "configure", "export-credentials",
            "--profile", profile, "--format", "process"});
        if (r.exitCode != 0) {
            String msg = r.stderr.isEmpty() ? r.stdout : r.stderr;
            throw new IOException("aws export-credentials failed for profile '" + profile
                + "': " + msg.trim());
        }
        String access = match(ACCESS_KEY, r.stdout);
        String secret = match(SECRET_KEY, r.stdout);
        String token = match(SESSION_TOKEN, r.stdout);
        if (access == null || secret == null) {
            throw new IOException("Could not parse credentials from aws CLI output for profile '"
                + profile + "'.");
        }
        return new Credentials(access, secret, token);
    }

    /** @return the region configured for the profile, or null. */
    public static String getRegion(String profile) {
        try {
            String exe = locate();
            Result r = run(new String[]{
                exe, "configure", "get", "region", "--profile", profile});
            if (r.exitCode == 0) {
                String out = r.stdout.trim();
                return out.isEmpty() ? null : out;
            }
        } catch (Exception ignored) {
            // best effort
        }
        return null;
    }

    private static final class Result {
        final int exitCode;
        final String stdout;
        final String stderr;

        Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static Result run(String[] command) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        // Drain stderr on a separate thread so a full pipe on either stream can
        // never deadlock the process.
        final StringBuilder errBuf = new StringBuilder();
        Thread errThread = new Thread(() -> {
            try {
                errBuf.append(readStream(p.getErrorStream()));
            } catch (IOException ignored) {
                // ignore
            }
        }, "aws-cli-stderr");
        errThread.setDaemon(true);
        errThread.start();
        try {
            String stdout = readStream(p.getInputStream());
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("aws CLI timed out after " + TIMEOUT_SECONDS + "s");
            }
            errThread.join(2000);
            return new Result(p.exitValue(), stdout, errBuf.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("Interrupted while running aws CLI", e);
        }
    }

    private static String readStream(java.io.InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static Pattern jsonString(String key) {
        return Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
    }

    private static String match(Pattern p, String json) {
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
