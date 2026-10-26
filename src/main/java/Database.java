import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

final class Database {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final long DAILY_WITHDRAWAL_LIMIT_CENTS = 3_000_000L;
    private final String jdbcUrl;

    Database() throws SQLException {
        this(defaultDatabasePath());
    }

    Database(Path file) throws SQLException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (Exception e) {
            throw new SQLException("Could not create the application data directory.", e);
        }
        jdbcUrl = "jdbc:sqlite:" + file.toAbsolutePath();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        password_salt TEXT NOT NULL,
                        balance_cents INTEGER NOT NULL CHECK (balance_cents >= 0),
                        created_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        account_id INTEGER NOT NULL REFERENCES accounts(id),
                        type TEXT NOT NULL,
                        amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),
                        related_account_id INTEGER REFERENCES accounts(id),
                        created_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_transactions_account_date ON transactions(account_id, created_at DESC)");
        }
    }

    private static Path defaultDatabasePath() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"), ".atm-simulator")
                : Path.of(localAppData, "ATMSimulator");
        return base.resolve("atm.db");
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    synchronized Accounts createAccount(String name, String hash, String salt, BigDecimal balance) throws SQLException {
        LocalDateTime created = LocalDateTime.now();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO accounts(name, password_hash, password_salt, balance_cents, created_at) VALUES (?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setString(2, hash);
            statement.setString(3, salt);
            statement.setLong(4, toCents(balance));
            statement.setString(5, DATE_FORMAT.format(created));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("The database did not generate an account ID.");
                return new Accounts(keys.getLong(1), name, hash, salt, balance, created);
            }
        }
    }

    Accounts findAccount(long id) throws SQLException {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM accounts WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readAccount(result) : null;
            }
        }
    }

    void deposit(Accounts account, BigDecimal amount) throws SQLException {
        long cents = toCents(amount);
        inTransaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE accounts SET balance_cents = balance_cents + ? WHERE id = ? AND balance_cents <= ?")) {
                update.setLong(1, cents);
                update.setLong(2, account.getId());
                update.setLong(3, Long.MAX_VALUE - cents);
                if (update.executeUpdate() != 1) {
                    throw new IllegalArgumentException("That deposit exceeds the supported account balance limit.");
                }
            }
            insertTransaction(connection, account.getId(), "DEPOSIT", cents, null);
        });
        account.applyDeposit(amount);
    }

    void withdraw(Accounts account, BigDecimal amount) throws SQLException {
        long cents = toCents(amount);
        inTransaction(connection -> {
            long todayTotal;
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT COALESCE(SUM(amount_cents), 0) FROM transactions WHERE account_id = ? AND type = 'WITHDRAWAL' AND date(created_at) = ?")) {
                query.setLong(1, account.getId());
                query.setString(2, LocalDate.now().toString());
                try (ResultSet result = query.executeQuery()) {
                    todayTotal = result.getLong(1);
                }
            }
            if (todayTotal > DAILY_WITHDRAWAL_LIMIT_CENTS - cents) {
                throw new IllegalArgumentException("The daily withdrawal limit is 30,000.");
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE accounts SET balance_cents = balance_cents - ? WHERE id = ? AND balance_cents >= ?")) {
                update.setLong(1, cents);
                update.setLong(2, account.getId());
                update.setLong(3, cents);
                if (update.executeUpdate() == 0) throw new IllegalArgumentException("Insufficient funds.");
            }
            insertTransaction(connection, account.getId(), "WITHDRAWAL", cents, null);
        });
        account.applyWithdrawal(amount);
    }

    void transfer(Accounts sender, Accounts recipient, BigDecimal amount) throws SQLException {
        long cents = toCents(amount);
        inTransaction(connection -> {
            try (PreparedStatement debit = connection.prepareStatement(
                    "UPDATE accounts SET balance_cents = balance_cents - ? WHERE id = ? AND balance_cents >= ?")) {
                debit.setLong(1, cents);
                debit.setLong(2, sender.getId());
                debit.setLong(3, cents);
                if (debit.executeUpdate() == 0) throw new IllegalArgumentException("Insufficient funds.");
            }
            try (PreparedStatement credit = connection.prepareStatement(
                    "UPDATE accounts SET balance_cents = balance_cents + ? WHERE id = ? AND balance_cents <= ?")) {
                credit.setLong(1, cents);
                credit.setLong(2, recipient.getId());
                credit.setLong(3, Long.MAX_VALUE - cents);
                if (credit.executeUpdate() != 1) {
                    throw new IllegalArgumentException("The recipient account balance exceeds the supported limit.");
                }
            }
            insertTransaction(connection, sender.getId(), "TRANSFER_OUT", cents, recipient.getId());
            insertTransaction(connection, recipient.getId(), "TRANSFER_IN", cents, sender.getId());
        });
        sender.applyWithdrawal(amount);
        recipient.applyDeposit(amount);
    }

    List<TransactionRecord> recentTransactions(long accountId, int limit) throws SQLException {
        String sql = """
                SELECT t.type, t.amount_cents, t.created_at, t.related_account_id, a.name
                FROM transactions t LEFT JOIN accounts a ON a.id = t.related_account_id
                WHERE t.account_id = ? ORDER BY t.id DESC LIMIT ?
                """;
        List<TransactionRecord> records = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, accountId);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {

                while (result.next()) {
                    long related = result.getLong(4);
                    Long relatedId = result.wasNull() ? null : related;
                    String type = result.getString(1);
                    BigDecimal amount = fromCents(result.getLong(2));
                    LocalDateTime timestamp = LocalDateTime.parse(result.getString(3), DATE_FORMAT);
                    String relatedName = result.getString(5);
                    records.add(new TransactionRecord(type, amount, timestamp, relatedId, relatedName));
                }
            }
        }
        return records;
    }

    private static Accounts readAccount(ResultSet result) throws SQLException {
        return new Accounts(result.getLong("id"), result.getString("name"), result.getString("password_hash"),
                result.getString("password_salt"), fromCents(result.getLong("balance_cents")),
                LocalDateTime.parse(result.getString("created_at"), DATE_FORMAT));
    }

    private static long toCents(BigDecimal amount) {
        try {
            return amount.setScale(2, java.math.RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amounts must use no more than two decimal places.");
        }
    }

    private static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    private static void insertTransaction(Connection connection, long accountId, String type, long cents, Long relatedId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO transactions(account_id, type, amount_cents, related_account_id, created_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setLong(1, accountId);
            statement.setString(2, type);
            statement.setLong(3, cents);
            if (relatedId == null) statement.setNull(4, Types.INTEGER);
            else statement.setLong(4, relatedId);
            statement.setString(5, DATE_FORMAT.format(LocalDateTime.now()));
            statement.executeUpdate();
        }
    }

    private static void requireUpdated(int count) throws SQLException {
        if (count != 1) throw new SQLException("The account could not be updated.");
    }

    private void inTransaction(SqlWork work) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }
}
