/*
 * DBeaver AWS RDS IAM Authentication extension.
 */
package com.example.dbeaver.ext.rdsiam;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Lists locally configured AWS profiles by reading the shared config files
 * (the same files {@code aws configure sso} / {@code aws configure} write).
 *
 * SSO profiles live in {@code ~/.aws/config} as {@code [profile NAME]} sections;
 * static-credential profiles may also appear in {@code ~/.aws/credentials} as
 * {@code [NAME]} sections. Honors the AWS_CONFIG_FILE and
 * AWS_SHARED_CREDENTIALS_FILE overrides. Pure JDK, no external dependencies.
 */
public final class AwsProfiles {

    private AwsProfiles() {
    }

    /** @return sorted, de-duplicated profile names; empty if none/unreadable. */
    public static List<String> list() {
        TreeSet<String> profiles = new TreeSet<>();
        readConfig(configFile(), profiles);
        readCredentials(credentialsFile(), profiles);
        return new ArrayList<>(profiles);
    }

    public static File configFile() {
        String override = System.getenv("AWS_CONFIG_FILE");
        if (override != null && !override.isEmpty()) {
            return new File(override);
        }
        return new File(awsDir(), "config");
    }

    public static File credentialsFile() {
        String override = System.getenv("AWS_SHARED_CREDENTIALS_FILE");
        if (override != null && !override.isEmpty()) {
            return new File(override);
        }
        return new File(awsDir(), "credentials");
    }

    private static File awsDir() {
        return new File(System.getProperty("user.home", "."), ".aws");
    }

    // ~/.aws/config: [default] and [profile NAME]; skip [sso-session NAME] etc.
    private static void readConfig(File file, TreeSet<String> out) {
        for (String section : sections(file)) {
            if (section.equals("default")) {
                out.add("default");
            } else if (section.startsWith("profile ")) {
                String name = section.substring("profile ".length()).trim();
                if (!name.isEmpty()) {
                    out.add(name);
                }
            }
        }
    }

    // ~/.aws/credentials: plain [NAME] sections.
    private static void readCredentials(File file, TreeSet<String> out) {
        for (String section : sections(file)) {
            if (!section.startsWith("sso-session ") && !section.startsWith("services ")) {
                out.add(section);
            }
        }
    }

    private static List<String> sections(File file) {
        List<String> result = new ArrayList<>();
        if (file == null || !file.isFile()) {
            return result;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("[") && t.endsWith("]")) {
                    result.add(t.substring(1, t.length() - 1).trim());
                }
            }
        } catch (IOException ignored) {
            // Unreadable config -> treat as no profiles.
        }
        return result;
    }
}
