# Computer Migration and Recovery

This project is designed to be recoverable from Git plus a small amount of private local state. Git is the source of truth for the application, tests, build wrapper, public documentation, schema migrations, and Compose definitions. It is not a backup of credentials, Docker volumes, IDE state, generated output, or user-home cache files.

## What a fresh computer needs

Install or enable:

- JDK 17;
- Git with access to the configured remote;
- a shell supported by the README commands (PowerShell on Windows or a Unix-like shell);
- internet access for the first Maven Wrapper run and dependency downloads;
- Docker Desktop and Compose only for the persistent MySQL path.

The repository pins Maven through `.mvn/wrapper/maven-wrapper.properties`. Use `mvnw.cmd` on Windows and `./mvnw` on Unix-like systems; do not depend on a globally installed Maven version.

## Restore from Git

Clone the repository and verify the branch before development:

```powershell
git clone https://github.com/MyKr-YSteinsK/config-center.git
Set-Location config-center
git switch master
git status --short --branch
```

Remote authentication and commit identity are computer-local. Configure GitHub access through Git Credential Manager or an SSH key, and set the new computer's commit identity without putting a token in the remote URL or repository:

```powershell
git config --global user.name "Your Name"
git config --global user.email "you@example.com"
```

The first fetch/push is the point at which the new computer may ask you to authenticate. A GitHub token, SSH private key, credential-manager entry, or organization proxy configuration must be transferred/configured separately and must never be committed.

Run the zero-dependency H2 path first:

```powershell
.\mvnw.cmd -q -B clean verify
```

Start the local server when the build passes:

```powershell
java -jar config-center-server/target/config-center-server-1.0.0.jar --spring.profiles.active=local
```

The local profile uses in-memory H2, so its data is intentionally not restored across process or computer changes.

## Private configuration

`.env` is intentionally ignored and is not present on the remote. On a new computer, create it from the tracked template and transfer only the values through a password manager or another secure channel:

```powershell
Copy-Item .env.example .env
```

The private file may contain `MYSQL_*`, `CONFIG_CENTER_*`, `SERVER_*`, and the optional `MAVEN_REPOSITORY` settings. Never commit `.env`, paste its values into an issue/chat, or use production credentials for local development. If a private Maven mirror is used, transfer its URL and any required authentication separately; the repository template contains only the public default.

The Compose path needs `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`, and `CONFIG_CENTER_API_KEY`. The manually managed `mysql` JAR path needs the `CONFIG_CENTER_DB_*` values and a non-root MySQL account. See the root README for the exact profile commands.

## Data that Git does not synchronize

- Compose MySQL data lives in the named Docker volume `config-center_mysql-data`; it is not in the repository. If the data matters, export a database dump to a location outside the repository, transfer it through a protected channel, and restore it on the new computer. A fresh `docker compose up -d --build` creates a new empty database and applies the tracked Flyway migrations.
- A manually managed MySQL database is external to this repository and needs its own backup/restore process.
- The client cache lives under the user home as `.config-center-client-cache.json` (with the legacy `.config-center-demo-client-cache.json` migration file). It is derived and untrusted; normally let the client regenerate it instead of migrating it.
- `.idea/`, `*.iml`, `target/`, logs, and Maven caches are local or generated state. They are not required for a clean checkout; IDE settings can be recreated after opening the cloned Maven project.

Do not use Git to synchronize any of these files. Do not reset the main Compose volume merely as part of migration.

## Before leaving a computer

1. Stop active local processes if they hold files needed for a clean check.
2. Run the required tests and inspect `git status --short --branch`.
3. Review `git diff` and `git diff --cached`; confirm no `.env`, credentials, IDE files, generated output, or temporary plans are included.
4. Create a focused commit and push it to the configured upstream. Confirm the branch is clean and ahead/behind is `0/0`.
5. Back up required MySQL data separately and record where the encrypted dump is stored. Do not put the dump in this repository.
6. Transfer private `.env` values and any private Maven mirror or database access details securely.

## Returning to another computer

On the other computer, fetch the remote branch, ensure the working tree is clean, and run the H2 verification before continuing:

```powershell
git fetch origin master
git switch master
git pull --ff-only origin master
git status --short --branch
.\mvnw.cmd -q -B clean verify
```

Restore `.env` only if the persistent or MySQL path is needed. Restore a database dump separately only when the data is required; code and schema migrations come from Git. Before switching back, repeat the clean commit/push and private-state backup steps above.

If GitHub access reports a Windows Schannel credential error, repair Git Credential Manager/sign-in on that computer. Do not disable TLS verification. A temporary alternate TLS backend may be used only as a local transport troubleshooting measure; it is not a repository setting.
