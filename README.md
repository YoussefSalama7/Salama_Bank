# SALAMA BANK ATM Simulator

A JavaFX desktop banking demo for SALAMA BANK, backed by SQLite. Accounts and transactions are persisted locally; passwords are stored as salted PBKDF2-HMAC-SHA256 hashes.

## Run

Use JDK 27 and Maven 3.9 or newer:

```text
mvn clean javafx:run
```

In IntelliJ IDEA, open this folder as a Maven project, select JDK 27 as the project SDK, reload the Maven project, then run `mvn javafx:run` from the Maven tool window or terminal. The JavaFX Maven plugin starts `ATMApp`.

The database is created automatically at `%LOCALAPPDATA%\ATMSimulator\atm.db` on Windows. On systems without `LOCALAPPDATA`, it uses `~/.atm-simulator/atm.db`.

New passwords must contain at least eight characters and include a letter and a number. Deposits, withdrawals, and transfers have a minimum of 50.00; cash withdrawals are limited to 30,000 per local calendar day. Five failed sign-in attempts trigger a three-minute in-memory lockout.

Run the automated account, persistence, transaction, and login-lockout tests with:

```text
mvn test
```

See [PROJECT_FILES.md](PROJECT_FILES.md) for a guide to the source, resources, configuration, and test files.
