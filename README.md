# DBeaver AWS RDS IAM Authentication

A DBeaver extension (Eclipse OSGi plugin) that adds an **"AWS RDS IAM"**
authentication model. Instead of a static database password, DBeaver logs in
with a short-lived token generated the same way as:

```bash
aws rds generate-db-auth-token --hostname <host> --port <port> --username <user> --region <region>
```

You configure the standard RDS connection settings (host, port, database user)
plus your AWS credentials (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and an
optional `AWS_SESSION_TOKEN`). At connect time the plugin generates a fresh IAM
token (valid 15 minutes) and hands it to the JDBC driver as the password.

## How it works

- **Auth model** (`RDSIAMAuthModel`) — extends DBeaver's native
  username/password model, but overrides `initAuthentication` to replace the
  password with a generated RDS IAM token.
- **Token generation** (`RDSAuthTokenGenerator`) — a **pure-JDK** SigV4 query
  pre-signer for the `rds-db` service. **No AWS SDK dependency** — nothing extra
  to bundle. It uses only `javax.crypto` (HmacSHA256) and `java.security`.
- **UI** (`RDSIAMAuthConfigurator`) — adds AWS Region + credential fields to the
  connection dialog. The region auto-detects from the RDS host name if left blank.

The AWS credentials are stored in DBeaver's secure credential storage (like any
other connection secret). The generated token itself is never persisted.

## Prerequisites on the AWS side

1. IAM database authentication must be **enabled** on the RDS instance/cluster.
2. The database user must be created for IAM auth, e.g. for PostgreSQL:
   ```sql
   CREATE USER iamuser WITH LOGIN;
   GRANT rds_iam TO iamuser;
   ```
3. The IAM principal (the access key you supply) needs `rds-db:connect` on the
   DB resource ARN.
4. **SSL/TLS is required** by RDS for IAM auth. The plugin handles this: the
   **Require SSL/TLS** checkbox (on by default) injects the driver's SSL
   properties at connect time — `sslmode=require` (PostgreSQL),
   `sslMode=REQUIRED` (MySQL), or `sslMode=TRUST` (MariaDB). This encrypts the
   connection but does **not** verify the server certificate. For full
   verification (`verify-full`), configure the connection's **SSL** tab with the
   RDS CA bundle instead — the plugin detects an enabled SSL tab (or any SSL
   connection property you set) and leaves your configuration untouched.

## Project layout

```
dbeaver-rds-iam-auth/
├── META-INF/MANIFEST.MF        # OSGi bundle manifest
├── plugin.xml                  # registers the auth model + UI configurator
├── build.properties
├── .project / .classpath       # Eclipse PDE project files
└── src/com/example/dbeaver/ext/rdsiam/
    ├── RDSAuthTokenGenerator.java   # dependency-free SigV4 token generator
    ├── RDSIAMConstants.java
    ├── RDSIAMCredentials.java
    ├── RDSIAMAuthModel.java
    └── ui/RDSIAMAuthConfigurator.java
```

## Building

No Eclipse/PDE or DBeaver source checkout is required. `build.sh` compiles the
plugin directly against the bundle jars inside your installed DBeaver and
packages an OSGi jar. It needs a JDK 21+ (DBeaver 26 ships Java 21 bytecode).

**macOS / Linux / WSL** (bash):

```bash
./build.sh            # -> com.example.dbeaver.ext.rdsiam_0.0.2.jar
./build.sh --install  # build and register into your local DBeaver
```

**Windows** (PowerShell) — native, no WSL needed:

```powershell
.\build.ps1            # -> com.example.dbeaver.ext.rdsiam_0.0.2.jar
.\build.ps1 -Install   # build and register into your local DBeaver
```

Overridable via env vars in both: `ECLIPSE_HOME` (dir containing `plugins/`),
`DBEAVER_APP` (default `/Applications/DBeaver.app`, or `C:\Program Files\DBeaver`
on Windows), `JAVA_HOME`, and `VERSION`. Both need a **JDK 21+** with `javac`.

You can still import the folder into an Eclipse PDE workspace with a DBeaver
target platform if you prefer that workflow — the `.project` / `.classpath` /
`build.properties` files are included.

## Installing

> **Do not use the `dropins` folder.** Stock DBeaver ships the p2 dropins
> reconciler bundle with `started=false`, so jars placed in `dropins/` are
> silently never loaded. Install the same way built-in bundles are registered.

`./build.sh --install` (macOS/Linux) or `.\build.ps1 -Install` (Windows) does
this for you:

1. Copies the jar into the DBeaver `plugins/` folder.
2. Adds a line to
   `<dbeaver>/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`:
   ```
   com.example.dbeaver.ext.rdsiam,0.0.2,plugins/com.example.dbeaver.ext.rdsiam_0.0.2.jar,4,false
   ```
   (a `.bak-rdsiam` backup is written first).

Then **fully quit DBeaver and relaunch once with `-clean`** (this flushes the
cached extension registry so the new auth model is picked up):

```bash
# macOS
/Applications/DBeaver.app/Contents/MacOS/dbeaver -clean
# Linux
<dbeaver>/dbeaver -clean
```

```powershell
# Windows
& "C:\Program Files\DBeaver\dbeaver.exe" -clean
```

The install location differs per OS (`plugins/` + `configuration/...bundles.info`):

- macOS: `/Applications/DBeaver.app/Contents/Eclipse/`
- Linux: `<dbeaver>/`
- Windows: `C:\Program Files\DBeaver\`

Then create/edit a PostgreSQL/MySQL/MariaDB connection and pick
**Authentication: AWS RDS IAM** in the connection's Main tab.

### Use an AWS CLI profile (SSO / assume-role / static)

If you have the **AWS CLI** installed and profiles configured (e.g. via
`aws configure sso` or `aws configure`), pick one from the **AWS Profile**
dropdown instead of pasting keys. The dropdown is populated from your
`~/.aws/config` and `~/.aws/credentials` (honoring `AWS_CONFIG_FILE` /
`AWS_SHARED_CREDENTIALS_FILE`).

When a profile is selected, the manual key fields are disabled, and at connect
time the plugin runs:

```
aws configure export-credentials --profile <name> --format process
```

to obtain credentials — which works for **SSO**, **assume-role**,
**credential_process**, and static-key profiles alike, transparently using and
refreshing cached SSO tokens. The region defaults to the profile's configured
region (`aws configure get region`) unless you override it.

Works on **macOS, Linux, and Windows**. Profiles are read from the same files on
every platform (`~/.aws/config` + `~/.aws/credentials`, i.e. `%UserProfile%\.aws`
on Windows).

Requirements / notes:

- AWS CLI **v2 ≥ 2.9** (for `export-credentials`).
- The plugin looks for the `aws` executable in these locations (in order), then
  falls back to `PATH`. Set **`AWS_CLI_PATH`** to override if yours is elsewhere:

  | OS | Locations searched |
  |----|--------------------|
  | Windows | `C:\Program Files\Amazon\AWSCLIV2\aws.exe`, `C:\Program Files (x86)\Amazon\AWSCLIV2\aws.exe`, then `aws.exe` on `PATH` |
  | Linux | `/usr/local/bin/aws`, `/usr/bin/aws`, `~/.local/bin/aws`, then `aws` on `PATH` |
  | macOS | `/usr/local/bin/aws`, `/opt/homebrew/bin/aws`, `/usr/bin/aws`, then `aws` on `PATH` |

  (Explicit locations matter because GUI-launched apps often inherit a trimmed
  `PATH`.)
- For SSO profiles, log in first: `aws sso login --profile <name>`. If the
  session is expired at connect time, the plugin surfaces an error telling you to
  run exactly that.
- Only the **database user** is required alongside a profile; the AWS keys are
  resolved from it.

### Paste-to-autofill

You don't have to fill the AWS fields one by one. Copy the credentials block your
platform gives you and paste it into **any** of the AWS fields — the panel
detects it and spreads the values into the right fields (Access Key ID / Secret /
Session Token / Region). Supported formats:

```bash
export AWS_ACCESS_KEY_ID="ASIA..."          # macOS / Linux (bash, zsh)
export AWS_SECRET_ACCESS_KEY="..."
export AWS_SESSION_TOKEN="..."
```
```powershell
$env:AWS_ACCESS_KEY_ID="ASIA..."            # Windows PowerShell
$env:AWS_SECRET_ACCESS_KEY="..."
$env:AWS_SESSION_TOKEN="..."
```
```bat
set AWS_ACCESS_KEY_ID=ASIA...               # Windows cmd (also: setx NAME "value")
set AWS_SECRET_ACCESS_KEY=...
set AWS_SESSION_TOKEN=...
```

Also recognized: plain `AWS_ACCESS_KEY_ID=...` lines, `AWS_REGION` /
`AWS_DEFAULT_REGION`, and `AWS_SECURITY_TOKEN` (treated as the session token).

> A DBeaver p2 update may rewrite `bundles.info` and drop the entry; just re-run
> `./build.sh --install` if the option disappears after an update.


## Version / API notes

- **Built and verified against DBeaver 26.1.1** (Community, macOS aarch64),
  Java 21.
- Wiring used (verified against the installed bundles):
  - Auth model extends `org.jkiss.dbeaver.model.impl.auth.AuthModelDatabaseNative`.
  - Auth model registered via the `org.jkiss.dbeaver.dataSourceAuth` extension
    point (`<authModel>` + `<datasource id="…"/>`).
  - UI panel implements `org.jkiss.dbeaver.ui.IObjectPropertyConfigurator` and is
    registered via `org.jkiss.dbeaver.ui.propertyConfigurator`
    (`<propertyConfigurator class="…AuthModel" uiClass="…Configurator"/>`).
- These interfaces have shifted across DBeaver versions. If you upgrade DBeaver
  and the plugin stops loading, rebuild with `./build.sh` against the new
  install; only the thin adapter classes (`RDSIAMAuthModel`,
  `RDSIAMAuthConfigurator`) touch DBeaver APIs. The token generator
  (`RDSAuthTokenGenerator`) has no DBeaver dependency.
- The token generator is validated to produce the same URL structure as
  `aws rds generate-db-auth-token` /
  `software.amazon.awssdk.services.rds.RdsUtilities#generateAuthenticationToken`.

## Security notes

- Prefer temporary STS credentials (with `AWS_SESSION_TOKEN`) over long-lived
  keys where possible.
- Tokens expire after 15 minutes; DBeaver regenerates one on each new
  connection/reconnect via `initAuthentication`.
