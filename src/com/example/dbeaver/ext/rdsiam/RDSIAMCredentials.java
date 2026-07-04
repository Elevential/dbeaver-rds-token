/*
 * DBeaver AWS RDS IAM Authentication extension.
 */
package com.example.dbeaver.ext.rdsiam;

import org.jkiss.dbeaver.model.impl.auth.AuthModelDatabaseNativeCredentials;

/**
 * Credentials for the AWS RDS IAM auth model.
 *
 * Extends the native database credentials (user name / password) so the
 * generated IAM token can be handed to the JDBC driver as the password,
 * while the AWS credentials used to generate that token are carried here.
 */
public class RDSIAMCredentials extends AuthModelDatabaseNativeCredentials {

    private String awsAccessKeyId;
    private String awsSecretAccessKey;
    private String awsSessionToken;
    private String region;
    private String profile;
    private boolean requireSsl = true;

    public String getAwsAccessKeyId() {
        return awsAccessKeyId;
    }

    public void setAwsAccessKeyId(String awsAccessKeyId) {
        this.awsAccessKeyId = awsAccessKeyId;
    }

    public String getAwsSecretAccessKey() {
        return awsSecretAccessKey;
    }

    public void setAwsSecretAccessKey(String awsSecretAccessKey) {
        this.awsSecretAccessKey = awsSecretAccessKey;
    }

    public String getAwsSessionToken() {
        return awsSessionToken;
    }

    public void setAwsSessionToken(String awsSessionToken) {
        this.awsSessionToken = awsSessionToken;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public boolean isRequireSsl() {
        return requireSsl;
    }

    public void setRequireSsl(boolean requireSsl) {
        this.requireSsl = requireSsl;
    }
}
