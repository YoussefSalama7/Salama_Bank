# Project File Guide

This guide describes the purpose of each application, configuration, resource, and test file.

## Application source

| File | Purpose |
|---|---|
| `src/main/java/ATMApp.java` | JavaFX entry point, branded screens, dashboard, form interaction, and navigation. |
| `src/main/java/Accounts.java` | Account model with immutable identity details and controlled balance operations. |
| `src/main/java/AccountManager.java` | Account creation, authentication coordination, validation, and transaction service entry points. |
| `src/main/java/Database.java` | SQLite schema initialization and parameterized persistence for accounts and transactions. |
| `src/main/java/PasswordHasher.java` | Salt generation and PBKDF2-HMAC-SHA256 password hashing and verification. |
| `src/main/java/TransactionRecord.java` | Immutable transaction-history record. |
| `src/main/java/LoginAttemptLimiter.java` | Five-failure sign-in counter and timed lockout calculation. |

## Resources and configuration

| File | Purpose |
|---|---|
| `src/main/resources/styles.css` | Dark navy and cyan JavaFX theme, including reusable SALAMA BANK logo styles. |
| `pom.xml` | Maven project settings, JavaFX and SQLite dependencies, Java version, and JavaFX run plugin. |
| `.gitignore` | Git exclusions for local build and IDE-generated files. |

## Tests and documentation

| File | Purpose |
|---|---|
| `src/test/java/AccountManagerTest.java` | Account creation, persistence, validation, daily withdrawal limit, transfers, and transaction-history tests. |
| `src/test/java/LoginAttemptLimiterTest.java` | Login-failure and three-minute lockout tests using a controllable clock. |
| `README.md` | Project overview, setup, run instructions, database location, and application rules. |
| `PROJECT_FILES.md` | This file-by-file project guide. |
