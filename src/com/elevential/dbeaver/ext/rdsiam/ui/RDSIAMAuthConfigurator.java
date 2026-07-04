/*
 * DBeaver AWS RDS IAM Authentication extension.
 */
package com.elevential.dbeaver.ext.rdsiam.ui;

import com.elevential.dbeaver.ext.rdsiam.AwsEnvVars;
import com.elevential.dbeaver.ext.rdsiam.AwsProfiles;
import com.elevential.dbeaver.ext.rdsiam.RDSIAMConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.access.DBAAuthModel;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.ui.IObjectPropertyConfigurator;
import org.jkiss.dbeaver.ui.UIUtils;

import java.util.List;
import java.util.Map;

/**
 * Connection-dialog panel for the AWS RDS IAM auth model.
 *
 * Renders the database user plus the AWS region and credentials used to
 * generate the IAM token. There is deliberately no static password field —
 * the password is generated at connect time.
 */
public class RDSIAMAuthConfigurator
    implements IObjectPropertyConfigurator<DBAAuthModel<?>, DBPDataSourceContainer> {

    /** First combo item, meaning "don't use a profile — use the keys below". */
    private static final String MANUAL_ITEM = "(none — enter keys manually)";

    private Runnable changeListener;
    private boolean autoFilling;

    private Text userNameText;
    private Combo profileCombo;
    private Text regionText;
    private Text accessKeyText;
    private Text secretKeyText;
    private Text sessionTokenText;
    private Button requireSslCheck;

    @Override
    public void createControl(Composite authPanel, DBAAuthModel<?> object, Runnable propertyChangeListener) {
        this.changeListener = propertyChangeListener;

        Label hint = new Label(authPanel, SWT.WRAP);
        hint.setText("Tip: paste your \"export AWS_…\" / \"set AWS_…\" / \"$env:AWS_…\" block "
            + "into any field to auto-fill the credentials.");
        GridData hintData = new GridData(GridData.FILL_HORIZONTAL);
        hintData.horizontalSpan = 2;
        hint.setLayoutData(hintData);

        UIUtils.createControlLabel(authPanel, "Database user");
        userNameText = new Text(authPanel, SWT.BORDER);
        userNameText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        userNameText.setToolTipText("Database user configured for IAM authentication (e.g. granted rds_iam).");
        attach(userNameText, null);

        UIUtils.createControlLabel(authPanel, "AWS Profile");
        profileCombo = new Combo(authPanel, SWT.DROP_DOWN | SWT.READ_ONLY);
        profileCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        profileCombo.add(MANUAL_ITEM);
        List<String> profiles = AwsProfiles.list();
        for (String p : profiles) {
            profileCombo.add(p);
        }
        profileCombo.select(0);
        profileCombo.setToolTipText(profiles.isEmpty()
            ? "No AWS profiles found in ~/.aws/config. Run 'aws configure sso' (or 'aws configure') to add one."
            : "Use credentials from a local AWS CLI profile (SSO / assume-role / static). "
                + "Resolved via the AWS CLI at connect time.");
        profileCombo.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> onProfileChanged()));

        UIUtils.createControlLabel(authPanel, "AWS Region");
        regionText = new Text(authPanel, SWT.BORDER);
        regionText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        regionText.setMessage("auto-detected from host");
        regionText.setToolTipText("Leave blank to derive the region from the RDS host name.");
        attach(regionText, "AWS_REGION");

        UIUtils.createControlLabel(authPanel, "AWS Access Key ID");
        accessKeyText = new Text(authPanel, SWT.BORDER);
        accessKeyText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        attach(accessKeyText, "AWS_ACCESS_KEY_ID");

        UIUtils.createControlLabel(authPanel, "AWS Secret Access Key");
        secretKeyText = new Text(authPanel, SWT.BORDER | SWT.PASSWORD);
        secretKeyText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        attach(secretKeyText, "AWS_SECRET_ACCESS_KEY");

        UIUtils.createControlLabel(authPanel, "AWS Session Token");
        sessionTokenText = new Text(authPanel, SWT.BORDER | SWT.PASSWORD);
        sessionTokenText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        sessionTokenText.setToolTipText("Optional. Required only for temporary (STS) credentials.");
        attach(sessionTokenText, "AWS_SESSION_TOKEN");

        requireSslCheck = new Button(authPanel, SWT.CHECK);
        requireSslCheck.setText("Require SSL/TLS (recommended — RDS IAM needs an encrypted connection)");
        requireSslCheck.setToolTipText("Injects the driver's SSL properties at connect time. "
            + "Ignored if you configure SSL yourself in the SSL tab.");
        GridData sslData = new GridData(GridData.FILL_HORIZONTAL);
        sslData.horizontalSpan = 2;
        requireSslCheck.setLayoutData(sslData);
        requireSslCheck.setSelection(true);
        requireSslCheck.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            if (changeListener != null) {
                changeListener.run();
            }
        }));
    }

    /**
     * Wires the paste-to-autofill behavior plus dialog change notification.
     *
     * The Verify listener is essential: it fires BEFORE insertion with the FULL
     * pasted string. Single-line Text controls on Windows (and GTK) truncate a
     * multi-line paste at the first line break, so a ModifyListener alone would
     * only ever see the first "export AWS_ACCESS_KEY_ID=…" line and fill nothing
     * but the access key. Here we detect the credentials block in the verify
     * event, suppress the raw insertion, and distribute the values ourselves.
     *
     * @param ownVar the AWS_* variable this field represents, or null (user name).
     */
    private void attach(Text field, String ownVar) {
        field.addVerifyListener(e -> {
            if (autoFilling) {
                return;
            }
            if (e.text != null && AwsEnvVars.looksLikeCredentialsBlock(e.text)) {
                final String block = e.text;
                e.doit = false; // don't insert the raw block into this field
                // Mutate the widgets outside the verify callback.
                field.getDisplay().asyncExec(() -> {
                    if (!field.isDisposed()) {
                        autoFill(field, ownVar, block);
                    }
                });
            }
        });
        // Fallback path (e.g. drag&drop or programmatic set that bypasses verify
        // interception): if a full block still lands as field content, spread it.
        field.addModifyListener(e -> onModify(field, ownVar));
    }

    private void onModify(Text source, String ownVar) {
        if (autoFilling) {
            // Ignore programmatic updates made while distributing a pasted block.
            return;
        }
        if (AwsEnvVars.looksLikeCredentialsBlock(source.getText())) {
            autoFill(source, ownVar, source.getText());
        } else if (changeListener != null) {
            changeListener.run();
        }
    }

    private void autoFill(Text source, String ownVar, String block) {
        Map<String, String> vars = AwsEnvVars.parse(block);
        autoFilling = true;
        try {
            applyIfPresent(accessKeyText, vars.get("AWS_ACCESS_KEY_ID"));
            applyIfPresent(secretKeyText, vars.get("AWS_SECRET_ACCESS_KEY"));
            applyIfPresent(sessionTokenText, AwsEnvVars.sessionToken(vars));
            applyIfPresent(regionText, AwsEnvVars.region(vars));
            // Replace the pasted block in the field the user pasted into with just
            // that field's own value (or clear it if the block held no such value).
            String own = ownValue(ownVar, vars);
            setText(source, own == null ? "" : own);
            if (!source.isDisposed()) {
                source.setSelection(source.getCharCount());
            }
        } finally {
            autoFilling = false;
        }
        if (changeListener != null) {
            changeListener.run();
        }
    }

    private static String ownValue(String ownVar, Map<String, String> vars) {
        if (ownVar == null) {
            return null;
        }
        switch (ownVar) {
            case "AWS_REGION":
                return AwsEnvVars.region(vars);
            case "AWS_SESSION_TOKEN":
                return AwsEnvVars.sessionToken(vars);
            default:
                return vars.get(ownVar);
        }
    }

    private static void applyIfPresent(Text field, String value) {
        if (field != null && !field.isDisposed() && value != null && !value.isEmpty()) {
            field.setText(value);
        }
    }

    /**
     * When a profile is selected, the manual key fields are unused: clear and
     * disable them (credentials come from the profile instead).
     */
    private void onProfileChanged() {
        boolean useProfile = isProfileSelected();
        if (useProfile) {
            autoFilling = true; // suppress the fields' modify listeners while clearing
            try {
                setText(accessKeyText, "");
                setText(secretKeyText, "");
                setText(sessionTokenText, "");
            } finally {
                autoFilling = false;
            }
        }
        setEnabled(accessKeyText, !useProfile);
        setEnabled(secretKeyText, !useProfile);
        setEnabled(sessionTokenText, !useProfile);
        if (changeListener != null) {
            changeListener.run();
        }
    }

    private boolean isProfileSelected() {
        return profileCombo != null && !profileCombo.isDisposed() && profileCombo.getSelectionIndex() > 0;
    }

    private String selectedProfile() {
        return isProfileSelected() ? profileCombo.getItem(profileCombo.getSelectionIndex()) : null;
    }

    private void selectProfileItem(String profile) {
        if (profileCombo == null || profileCombo.isDisposed()) {
            return;
        }
        int idx = 0;
        if (profile != null && !profile.isEmpty()) {
            int found = profileCombo.indexOf(profile);
            if (found < 0) {
                // Saved profile is no longer in the local config; keep it selectable.
                profileCombo.add(profile);
                found = profileCombo.indexOf(profile);
            }
            idx = found;
        }
        profileCombo.select(idx);
    }

    @Override
    public void loadSettings(DBPDataSourceContainer dataSource) {
        DBPConnectionConfiguration cfg = dataSource.getConnectionConfiguration();
        setText(userNameText, cfg.getUserName());
        selectProfileItem(cfg.getAuthProperty(RDSIAMConstants.PROP_PROFILE));
        setText(regionText, cfg.getAuthProperty(RDSIAMConstants.PROP_REGION));
        setText(accessKeyText, cfg.getAuthProperty(RDSIAMConstants.PROP_ACCESS_KEY_ID));
        setText(secretKeyText, cfg.getAuthProperty(RDSIAMConstants.PROP_SECRET_ACCESS_KEY));
        setText(sessionTokenText, cfg.getAuthProperty(RDSIAMConstants.PROP_SESSION_TOKEN));
        if (requireSslCheck != null && !requireSslCheck.isDisposed()) {
            // Default to enabled unless explicitly turned off.
            requireSslCheck.setSelection(!"false".equals(cfg.getAuthProperty(RDSIAMConstants.PROP_REQUIRE_SSL)));
        }
        onProfileChanged();
    }

    @Override
    public void saveSettings(DBPDataSourceContainer dataSource) {
        DBPConnectionConfiguration cfg = dataSource.getConnectionConfiguration();
        cfg.setUserName(trim(userNameText));
        cfg.setAuthProperty(RDSIAMConstants.PROP_PROFILE, selectedProfile());
        cfg.setAuthProperty(RDSIAMConstants.PROP_REGION, trimToNull(regionText));
        cfg.setAuthProperty(RDSIAMConstants.PROP_ACCESS_KEY_ID, trimToNull(accessKeyText));
        cfg.setAuthProperty(RDSIAMConstants.PROP_SECRET_ACCESS_KEY, trimToNull(secretKeyText));
        cfg.setAuthProperty(RDSIAMConstants.PROP_SESSION_TOKEN, trimToNull(sessionTokenText));
        boolean requireSsl = requireSslCheck == null || requireSslCheck.isDisposed() || requireSslCheck.getSelection();
        cfg.setAuthProperty(RDSIAMConstants.PROP_REQUIRE_SSL, requireSsl ? "true" : "false");
    }

    @Override
    public void resetSettings(DBPDataSourceContainer dataSource) {
        loadSettings(dataSource);
    }

    @Override
    public boolean isComplete() {
        if (isProfileSelected()) {
            // Credentials come from the profile; only the DB user is required here.
            return notEmpty(userNameText);
        }
        return notEmpty(userNameText) && notEmpty(accessKeyText) && notEmpty(secretKeyText);
    }

    private static void setEnabled(Text field, boolean enabled) {
        if (field != null && !field.isDisposed()) {
            field.setEnabled(enabled);
        }
    }

    private static boolean notEmpty(Text text) {
        return text != null && !text.isDisposed() && !text.getText().trim().isEmpty();
    }

    private static void setText(Text text, String value) {
        if (text != null && !text.isDisposed()) {
            text.setText(value == null ? "" : value);
        }
    }

    private static String trim(Text text) {
        return (text == null || text.isDisposed()) ? "" : text.getText().trim();
    }

    private static String trimToNull(Text text) {
        String value = trim(text);
        return value.isEmpty() ? null : value;
    }
}
