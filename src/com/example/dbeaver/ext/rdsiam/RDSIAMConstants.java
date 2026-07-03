/*
 * DBeaver AWS RDS IAM Authentication extension.
 */
package com.example.dbeaver.ext.rdsiam;

/**
 * Shared identifiers and connection-configuration property keys.
 */
public final class RDSIAMConstants {

    /** Auth model id, referenced from plugin.xml. */
    public static final String AUTH_MODEL_ID = "aws_rds_iam";

    /** Auth-property key for the AWS access key id. */
    public static final String PROP_ACCESS_KEY_ID = "@rds-iam.accessKeyId";

    /** Auth-property key for the AWS secret access key (stored securely). */
    public static final String PROP_SECRET_ACCESS_KEY = "@rds-iam.secretAccessKey";

    /** Auth-property key for the AWS session token (stored securely). */
    public static final String PROP_SESSION_TOKEN = "@rds-iam.sessionToken";

    /** Auth-property key for the AWS region (may be blank -> parsed from host). */
    public static final String PROP_REGION = "@rds-iam.region";

    /**
     * Auth-property key for a selected AWS CLI profile. When set, credentials are
     * resolved from that profile (SSO / assume-role / static) via the AWS CLI at
     * connect time, and the manually entered keys are ignored.
     */
    public static final String PROP_PROFILE = "@rds-iam.profile";

    private RDSIAMConstants() {
    }
}
