/*
 * DBeaver AWS RDS IAM Authentication extension.
 */
package com.example.dbeaver.ext.rdsiam;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.impl.auth.AuthModelDatabaseNative;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

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

        // Hand the generated token to the base model as the password; it will be
        // injected into the JDBC connection properties by collectConnectionProperties.
        credentials.setUserPassword(token);
        return super.initAuthentication(monitor, dataSource, credentials, configuration, connectProps);
    }
}
