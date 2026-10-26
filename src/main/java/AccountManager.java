import java.math.BigDecimal;
import java.sql.SQLException;

public final class AccountManager {
    private final Database database;

    public AccountManager() throws SQLException {
        database = new Database();
    }

    AccountManager(Database database) {
        this.database = database;
    }

    public Accounts createAccount(String name, char[] password, BigDecimal startingBalance) throws SQLException {
        String cleanName = name == null ? "" : name.strip();
        if (cleanName.isBlank()) throw new IllegalArgumentException("Enter the account holder's full name.");
        validatePassword(password);
        validateMoney(startingBalance, "Initial balance");
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(password, salt);
        return database.createAccount(cleanName, hash, salt, startingBalance);
    }

    public Accounts login(long id, char[] password) throws SQLException {
        if (id <= 0) return null;
        Accounts account = database.findAccount(id);
        if (account == null || password == null
                || !PasswordHasher.verify(password, account.getPasswordSalt(), account.getPasswordHash())) return null;
        return account;
    }

    Accounts findAccount(long id) throws SQLException {
        return database.findAccount(id);
    }

    void deposit(Accounts account, BigDecimal amount) throws SQLException {
        validateMinimum(amount, "Deposit");
        database.deposit(account, amount);
    }

    void withdraw(Accounts account, BigDecimal amount) throws SQLException {
        validateMinimum(amount, "Withdrawal");
        database.withdraw(account, amount);
    }

    void transfer(Accounts sender, long recipientId, BigDecimal amount) throws SQLException {
        validateMinimum(amount, "Transfer");
        if (sender.getId() == recipientId)
            throw new IllegalArgumentException("You cannot transfer to your own account.");
        Accounts recipient = database.findAccount(recipientId);
        if (recipient == null) throw new IllegalArgumentException("No account was found with that recipient ID.");
        database.transfer(sender, recipient, amount);
    }

    java.util.List<TransactionRecord> recentTransactions(long accountId, int limit) throws SQLException {
        return database.recentTransactions(accountId, limit);
    }

    private static void validateMinimum(BigDecimal amount, String action) {
        validateMoney(amount, action + " amount");
        if (amount.compareTo(new BigDecimal("50.00")) < 0)
            throw new IllegalArgumentException(action + " minimum is 50.00.");
    }

    private static void validateMoney(BigDecimal amount, String field) {
        if (amount == null) throw new IllegalArgumentException("Enter a valid " + field.toLowerCase() + ".");
        if (amount.signum() < 0) throw new IllegalArgumentException(field + " cannot be negative.");
        try {
            amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
            amount.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(field + " must have no more than two decimal places and be within the supported range.");
        }
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length < 8
                || password.length > 128
                || !containsLetterAndDigit(password)) {
            throw new IllegalArgumentException("Use a password with at least 8 characters, including a letter and a number.");
        }
    }

    private static boolean containsLetterAndDigit(char[] password) {
        boolean letter = false;
        boolean digit = false;
        for (char character : password) {
            letter |= Character.isLetter(character);
            digit |= Character.isDigit(character);
        }
        return letter && digit;
    }
}
