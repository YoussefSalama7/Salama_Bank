import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

public final class Accounts {
    private final long id;
    private final String name;
    private final String passwordHash;
    private final String passwordSalt;
    private final LocalDateTime dateCreated;
    private BigDecimal balance;

    Accounts(long id, String name, String passwordHash, String passwordSalt,
             BigDecimal balance, LocalDateTime dateCreated) {
        this.id = id;
        this.name = Objects.requireNonNull(name);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.passwordSalt = Objects.requireNonNull(passwordSalt);
        this.balance = Objects.requireNonNull(balance);
        this.dateCreated = Objects.requireNonNull(dateCreated);
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public LocalDateTime getDateCreated() {
        return dateCreated;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    String getPasswordSalt() {
        return passwordSalt;
    }

    void applyDeposit(BigDecimal amount) {
        validateAmount(amount);
        BigDecimal updatedBalance = balance.add(amount);
        requireSupportedAmount(updatedBalance);
        balance = updatedBalance;
    }

    void applyWithdrawal(BigDecimal amount) {
        validateAmount(amount);
        if (amount.compareTo(balance) > 0) throw new IllegalArgumentException("Insufficient funds.");
        balance = balance.subtract(amount);
    }

    private static void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0)
            throw new IllegalArgumentException("Amount must be greater than zero.");
        requireSupportedAmount(amount);
    }

    private static void requireSupportedAmount(BigDecimal amount) {
        try {
            amount.setScale(2, java.math.RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amounts must use no more than two decimal places and be within the supported range.");
        }
    }
}
