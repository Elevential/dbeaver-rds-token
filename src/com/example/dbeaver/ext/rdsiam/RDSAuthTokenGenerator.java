/*
 * DBeaver AWS RDS IAM Authentication extension.
 *
 * Generates an RDS IAM database authentication token, equivalent to the
 * AWS CLI command:
 *
 *   aws rds generate-db-auth-token \
 *       --hostname <host> --port <port> --username <user> --region <region>
 *
 * The token is a SigV4 pre-signed URL for the "rds-db" service with the
 * scheme stripped off. It is used in place of a database password and is
 * valid for 15 minutes. This is implemented with the plain JDK crypto
 * primitives so the plugin has no external (AWS SDK) dependencies.
 */
package com.example.dbeaver.ext.rdsiam;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Pure-JDK generator for AWS RDS IAM database auth tokens (SigV4 query signing).
 */
public final class RDSAuthTokenGenerator {

    private static final String SERVICE_NAME = "rds-db";
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String ACTION = "connect";
    private static final long EXPIRES_SECONDS = 900; // 15 minutes, the maximum RDS allows
    private static final String EMPTY_PAYLOAD_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final DateTimeFormatter AMZ_DATE =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US);
    private static final DateTimeFormatter SHORT_DATE =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US);

    private RDSAuthTokenGenerator() {
    }

    /**
     * Generates an RDS IAM auth token.
     *
     * @param host         RDS endpoint host name (e.g. mydb.abc123.us-east-1.rds.amazonaws.com)
     * @param port         database port (e.g. 5432)
     * @param dbUser       database user configured for IAM authentication
     * @param region       AWS region (e.g. us-east-1)
     * @param accessKeyId  AWS access key id
     * @param secretKey    AWS secret access key
     * @param sessionToken AWS session token, or null/empty for long-term credentials
     * @return the auth token to be used as the database password
     */
    public static String generate(
        String host,
        int port,
        String dbUser,
        String region,
        String accessKeyId,
        String secretKey,
        String sessionToken) throws Exception {

        if (isEmpty(host)) {
            throw new IllegalArgumentException("RDS host is required");
        }
        if (port <= 0) {
            throw new IllegalArgumentException("RDS port is required");
        }
        if (isEmpty(dbUser)) {
            throw new IllegalArgumentException("Database user is required");
        }
        if (isEmpty(region)) {
            throw new IllegalArgumentException("AWS region could not be determined; please set it explicitly");
        }
        if (isEmpty(accessKeyId) || isEmpty(secretKey)) {
            throw new IllegalArgumentException("AWS access key id and secret access key are required");
        }

        String canonicalHost = host.toLowerCase(Locale.US);

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String amzDate = now.format(AMZ_DATE);
        String shortDate = now.format(SHORT_DATE);
        String credentialScope = shortDate + "/" + region + "/" + SERVICE_NAME + "/aws4_request";

        // Canonical query string. Keys must be sorted; capital-letter params
        // (X-Amz-*) sort before lower-case ones per byte ordering, but we build
        // the full sorted set explicitly below.
        StringBuilder query = new StringBuilder();
        query.append("Action=").append(uriEncode(ACTION));
        query.append("&DBUser=").append(uriEncode(dbUser));
        query.append("&X-Amz-Algorithm=").append(uriEncode(ALGORITHM));
        query.append("&X-Amz-Credential=").append(uriEncode(accessKeyId + "/" + credentialScope));
        query.append("&X-Amz-Date=").append(uriEncode(amzDate));
        query.append("&X-Amz-Expires=").append(EXPIRES_SECONDS);
        if (!isEmpty(sessionToken)) {
            query.append("&X-Amz-Security-Token=").append(uriEncode(sessionToken));
        }
        query.append("&X-Amz-SignedHeaders=").append(uriEncode("host"));

        // The signed host header includes the port.
        String hostHeaderValue = canonicalHost + ":" + port;
        String canonicalHeaders = "host:" + hostHeaderValue + "\n";
        String signedHeaders = "host";

        String canonicalRequest = "GET" + "\n"
            + "/" + "\n"
            + query + "\n"
            + canonicalHeaders + "\n"
            + signedHeaders + "\n"
            + EMPTY_PAYLOAD_SHA256;

        String stringToSign = ALGORITHM + "\n"
            + amzDate + "\n"
            + credentialScope + "\n"
            + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] signingKey = signingKey(secretKey, shortDate, region);
        String signature = hex(hmacSha256(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));

        return hostHeaderValue + "/?" + query + "&X-Amz-Signature=" + signature;
    }

    /**
     * Best-effort extraction of the AWS region from an RDS endpoint host name.
     * Handles both the standard form (…​.&lt;region&gt;.rds.amazonaws.com) and the
     * China partition (…​.&lt;region&gt;.rds.amazonaws.com.cn).
     *
     * @return the region, or null if it could not be determined.
     */
    public static String regionFromHost(String host) {
        if (isEmpty(host)) {
            return null;
        }
        String[] parts = host.toLowerCase(Locale.US).split("\\.");
        for (int i = 1; i < parts.length; i++) {
            if ("rds".equals(parts[i])) {
                return parts[i - 1];
            }
        }
        return null;
    }

    private static byte[] signingKey(String secretKey, String shortDate, String region) throws Exception {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, shortDate.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmacSha256(kDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] kService = hmacSha256(kRegion, SERVICE_NAME.getBytes(StandardCharsets.UTF_8));
        return hmacSha256(kService, "aws4_request".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * RFC 3986 URI encoding as required by SigV4 (space -&gt; %20, and
     * ~ / - / _ / . left unencoded).
     */
    private static String uriEncode(String value) throws UnsupportedEncodingException {
        String encoded = URLEncoder.encode(value, "UTF-8");
        return encoded
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~");
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
