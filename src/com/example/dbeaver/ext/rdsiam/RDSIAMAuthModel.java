/*
 * DBeaver AWS RDS IAM Authentication extension.
 */
package com.example.dbeaver.ext.rdsiam;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.impl.auth.AuthModelDatabaseNative;
import org.jkiss.dbeaver.model.net.DBWHandlerConfiguration;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.Locale;
import java.util.Properties;

/**
 * Authentication model that logs into an RDS instance using an IAM auth token
 * instead of a static password.
 *
 * The user still enters a database user name; the "password" handed to the JDBC
 * driver is a freshly generated, short-lived RDS IAM token derived from the
 * supplied AWS credentials. Generation is local (SigV4 signing) and needs no
 * network access.
 */
public class RDSIAMAuthModel extends AuthModelDatabaseNative<RDSIAMCredentials> {

    private static final Log log = Log.getLog(RDSIAMAuthModel.class);

    @Override
    public RDSIAMCredentials createCredentials() {
        return new RDSIAMCredentials();
    }

    @Override
    public RDSIAMCredentials loadCredentials(
        DBPDataSourceContainer dataSource,
        DBPConnectionConfiguration configuration) {

        RDSIAMCredentials credentials = super.loadCredentials(dataSource, configuration);
        credentials.setAwsAccessKeyId(configuration.getAuthProperty(RDSIAMConstants.PROP_ACCESS_KEY_ID));
        credentials.setAwsSecretAccessKey(configuration.getAuthProperty(RDSIAMConstants.PROP_SECRET_ACCESS_KEY));
        credentials.setAwsSessionToken(configuration.getAuthProperty(RDSIAMConstants.PROP_SESSION_TOKEN));
        credentials.setRegion(configuration.getAuthProperty(RDSIAMConstants.PROP_REGION));
        credentials.setProfile(configuration.getAuthProperty(RDSIAMConstants.PROP_PROFILE));
        // Default to requiring SSL unless explicitly disabled.
        credentials.setRequireSsl(!"false".equals(configuration.getAuthProperty(RDSIAMConstants.PROP_REQUIRE_SSL)));
        return credentials;
    }

    @Override
    public void saveCredentials(
        DBPDataSourceContainer dataSource,
        DBPConnectionConfiguration configuration,
        RDSIAMCredentials credentials) {

        configuration.setAuthProperty(RDSIAMConstants.PROP_ACCESS_KEY_ID, credentials.getAwsAccessKeyId());
        configuration.setAuthProperty(RDSIAMConstants.PROP_SECRET_ACCESS_KEY, credentials.getAwsSecretAccessKey());
        configuration.setAuthProperty(RDSIAMConstants.PROP_SESSION_TOKEN, credentials.getAwsSessionToken());
        configuration.setAuthProperty(RDSIAMConstants.PROP_REGION, credentials.getRegion());
        configuration.setAuthProperty(RDSIAMConstants.PROP_PROFILE, credentials.getProfile());
        configuration.setAuthProperty(RDSIAMConstants.PROP_REQUIRE_SSL, credentials.isRequireSsl() ? "true" : "false");
        // The generated IAM token is transient; never persist it as a password.
        credentials.setUserPassword(null);
        super.saveCredentials(dataSource, configuration, credentials);
    }

    @Override
    public Object initAuthentication(
        DBRProgressMonitor monitor,
        DBPDataSource dataSource,
        RDSIAMCredentials credentials,
        DBPConnectionConfiguration configuration,
        Properties connectProps) throws DBException {

        String host = configuration.getHostName();
        String portStr = configuration.getHostPort();
        String user = credentials.getUserName();

        // Resolve the AWS credentials to sign with. If a CLI profile is chosen,
        // ask the AWS CLI to export credentials for it (handles SSO, assume-role,
        // static keys); otherwise use the manually entered keys.
        String accessKeyId = credentials.getAwsAccessKeyId();
        String secretAccessKey = credentials.getAwsSecretAccessKey();
        String sessionToken = credentials.getAwsSessionToken();
        String region = credentials.getRegion();

        String profile = credentials.getProfile();
        if (profile != null && !profile.isEmpty()) {
            try {
                monitor.subTask("Resolving AWS credentials for profile '" + profile + "'");
                AwsCli.Credentials resolved = AwsCli.exportCredentials(profile);
                accessKeyId = resolved.accessKeyId;
                secretAccessKey = resolved.secretAccessKey;
                sessionToken = resolved.sessionToken;
            } catch (Exception e) {
                throw new DBException("Could not obtain AWS credentials for profile '" + profile
                    + "'. If this is an SSO profile, run 'aws sso login --profile " + profile
                    + "' first.\n" + e.getMessage(), e);
            }
            if (region == null || region.isEmpty()) {
                region = AwsCli.getRegion(profile);
            }
        }

        if (region == null || region.isEmpty()) {
            region = RDSAuthTokenGenerator.regionFromHost(host);
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            throw new DBException("Invalid RDS port: '" + portStr + "'");
        }

        String token;
        try {
            monitor.subTask("Generating AWS RDS IAM auth token");
            token = RDSAuthTokenGenerator.generate(
                host,
                port,
                user,
                region,
                accessKeyId,
                secretAccessKey,
                sessionToken);
        } catch (DBException e) {
            throw e;
        } catch (Exception e) {
            throw new DBException("Failed to generate RDS IAM auth token: " + e.getMessage(), e);
        }

        log.debug("Generated RDS IAM auth token for user '" + user + "' on host '" + host + "' (region " + region + ")");

        // RDS IAM requires an encrypted connection — inject SSL properties unless
        // the user opted out or has already configured SSL themselves.
        if (credentials.isRequireSsl()) {
            enforceSsl(dataSource, configuration, connectProps);
        }

        // Hand the generated token to the base model as the password; it will be
        // injected into the JDBC connection properties by collectConnectionProperties.
        credentials.setUserPassword(token);
        return super.initAuthentication(monitor, dataSource, credentials, configuration, connectProps);
    }

    /**
     * Injects the driver-appropriate SSL properties so the JDBC connection is
     * encrypted (required by RDS IAM). No-ops when the user has already configured
     * SSL (via the SSL tab or an explicit SSL connection property), so we never
     * override a stricter, user-chosen configuration.
     */
    private void enforceSsl(DBPDataSource dataSource, DBPConnectionConfiguration cfg, Properties props) {
        // Respect an explicitly enabled SSL network handler (the "SSL" tab).
        if (isHandlerEnabled(cfg, "postgre_ssl") || isHandlerEnabled(cfg, "mysql_ssl")) {
            return;
        }

        String url = cfg.getUrl();
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        String driverId = "";
        String providerId = "";
        try {
            DBPDriver driver = dataSource.getContainer().getDriver();
            driverId = String.valueOf(driver.getId()).toLowerCase(Locale.ROOT);
            providerId = String.valueOf(driver.getProviderId()).toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            // fall back to URL-only detection
        }

        boolean maria = u.contains(":mariadb:") || driverId.contains("maria");
        boolean postgres = u.contains(":postgresql:") || providerId.contains("postgre");
        boolean mysql = !maria && (u.contains(":mysql:") || providerId.contains("mysql"));

        if (postgres) {
            // pgjdbc: 'require' encrypts without CA verification (use the SSL tab
            // with the RDS CA bundle for verify-full). sslmode=require implies SSL,
            // so 'ssl=true' is not needed.
            setSslPropIfAbsent(cfg, props, "sslmode", "require", "ssl", "sslmode", "sslfactory");
        } else if (maria) {
            // MariaDB Connector/J enum: DISABLE / TRUST / VERIFY_CA / VERIFY_FULL.
            setSslPropIfAbsent(cfg, props, "sslMode", "TRUST", "sslMode", "useSsl", "useSSL");
        } else if (mysql) {
            // MySQL Connector/J enum: DISABLED / PREFERRED / REQUIRED / VERIFY_*.
            setSslPropIfAbsent(cfg, props, "sslMode", "REQUIRED", "sslMode", "useSSL", "useSsl");
        }
    }

    private static void setSslPropIfAbsent(
        DBPConnectionConfiguration cfg, Properties props, String key, String value, String... conflictKeys) {
        for (String k : conflictKeys) {
            if (props.containsKey(k) || cfg.getProperty(k) != null) {
                return; // user already configured SSL for this driver — leave it alone
            }
        }
        props.put(key, value);
    }

    private static boolean isHandlerEnabled(DBPConnectionConfiguration cfg, String handlerId) {
        DBWHandlerConfiguration handler = cfg.getHandler(handlerId);
        return handler != null && handler.isEnabled();
    }
}
