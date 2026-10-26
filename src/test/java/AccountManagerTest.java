import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class AccountManagerTest {
    @TempDir
    Path directory;

    private Path databaseFile;
    private AccountManager manager;

    @BeforeEach
    void setUp() throws SQLException {
        databaseFile = directory.resolve("atm.db");
        manager = new AccountManager(new Database(databaseFile));
    }

    @Test
    void createsAccountsWithHashedCredentialsAndPersistsThem() throws SQLException {
        Accounts created = manager.createAccount("  Alex Morgan  ", "secure123".toCharArray(), money("125.50"));

        AccountManager reopened = new AccountManager(new Database(databaseFile));
        Accounts loaded = reopened.login(created.getId(), "secure123".toCharArray());

        assertNotNull(loaded);
        assertEquals("Alex Morgan", loaded.getName());
        assertEquals(money("125.50"), loaded.getBalance());
        assertEquals(created.getDateCreated(), loaded.getDateCreated());
        assertNull(reopened.login(created.getId(), "wrong123".toCharArray()));
    }

    @Test
    void enforcesDepositAndWithdrawalMinimumsAndInsufficientFunds() throws SQLException {
        Accounts account = manager.createAccount("Alex Morgan", "secure123".toCharArray(), money("100.00"));

        assertThrows(IllegalArgumentException.class, () -> manager.deposit(account, money("49.99")));
        assertThrows(IllegalArgumentException.class, () -> manager.withdraw(account, money("49.99")));
        assertThrows(IllegalArgumentException.class, () -> manager.withdraw(account, money("150.00")));

        manager.deposit(account, money("50.00"));
        manager.withdraw(account, money("50.00"));

        assertEquals(money("100.00"), manager.findAccount(account.getId()).getBalance());
        assertEquals(2, manager.recentTransactions(account.getId(), 12).size());
    }

    @Test
    void enforcesPersistedDailyWithdrawalLimit() throws SQLException {
        Accounts account = manager.createAccount("Alex Morgan", "secure123".toCharArray(), money("31000.00"));
        manager.withdraw(account, money("29950.00"));
        manager.withdraw(account, money("50.00"));

        assertThrows(IllegalArgumentException.class, () -> manager.withdraw(account, money("50.00")));

        AccountManager reopened = new AccountManager(new Database(databaseFile));
        Accounts persisted = reopened.findAccount(account.getId());
        assertEquals(money("1000.00"), persisted.getBalance());
        assertEquals(2, reopened.recentTransactions(account.getId(), 12).size());
    }

    @Test
    void transfersBetweenAccountsAndRecordsBothSides() throws SQLException {
        Accounts sender = manager.createAccount("Alex Morgan", "secure123".toCharArray(), money("500.00"));
        Accounts recipient = manager.createAccount("Sam Taylor", "secure456".toCharArray(), money("100.00"));

        manager.transfer(sender, recipient.getId(), money("125.00"));

        assertEquals(money("375.00"), manager.findAccount(sender.getId()).getBalance());
        assertEquals(money("225.00"), manager.findAccount(recipient.getId()).getBalance());
        TransactionRecord outgoing = manager.recentTransactions(sender.getId(), 12).getFirst();
        TransactionRecord incoming = manager.recentTransactions(recipient.getId(), 12).getFirst();
        assertEquals("TRANSFER_OUT", outgoing.type());
        assertEquals(recipient.getId(), outgoing.relatedAccountId());
        assertEquals("Sam Taylor", outgoing.relatedAccountName());
        assertEquals("TRANSFER_IN", incoming.type());
        assertEquals(sender.getId(), incoming.relatedAccountId());
    }

    @Test
    void failedTransferDoesNotPartiallyUpdateEitherAccount() throws SQLException {
        Accounts sender = manager.createAccount("Alex Morgan", "secure123".toCharArray(), money("500.00"));
        Accounts recipient = manager.createAccount("Sam Taylor", "secure456".toCharArray(),
                BigDecimal.valueOf(Long.MAX_VALUE, 2));

        assertThrows(IllegalArgumentException.class, () -> manager.transfer(sender, recipient.getId(), money("50.00")));

        assertEquals(money("500.00"), manager.findAccount(sender.getId()).getBalance());
        assertEquals(BigDecimal.valueOf(Long.MAX_VALUE, 2), manager.findAccount(recipient.getId()).getBalance());
        assertTrue(manager.recentTransactions(sender.getId(), 12).isEmpty());
        assertTrue(manager.recentTransactions(recipient.getId(), 12).isEmpty());
    }

    @Test
    void rejectsInvalidAccountAndAmountInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.createAccount(" ", "secure123".toCharArray(), money("0.00")));
        assertThrows(IllegalArgumentException.class,
                () -> manager.createAccount("Alex Morgan", "short1".toCharArray(), money("0.00")));
        assertThrows(IllegalArgumentException.class,
                () -> manager.createAccount("Alex Morgan", "secure123".toCharArray(), money("-1.00")));
        assertThrows(IllegalArgumentException.class,
                () -> manager.createAccount("Alex Morgan", "secure123".toCharArray(), money("1.001")));
    }

    @Test
    void rejectsInvalidTransfersWithoutChangingBalances() throws SQLException {
        Accounts sender = manager.createAccount("Alex Morgan", "secure123".toCharArray(), money("500.00"));
        Accounts recipient = manager.createAccount("Sam Taylor", "secure456".toCharArray(), money("100.00"));

        assertThrows(IllegalArgumentException.class, () -> manager.transfer(sender, recipient.getId(), money("49.99")));
        assertThrows(IllegalArgumentException.class, () -> manager.transfer(sender, sender.getId(), money("50.00")));
        assertThrows(IllegalArgumentException.class, () -> manager.transfer(sender, Long.MAX_VALUE, money("50.00")));
        assertThrows(IllegalArgumentException.class, () -> manager.transfer(sender, recipient.getId(), money("600.00")));

        assertEquals(money("500.00"), manager.findAccount(sender.getId()).getBalance());
        assertEquals(money("100.00"), manager.findAccount(recipient.getId()).getBalance());
        assertTrue(manager.recentTransactions(sender.getId(), 12).isEmpty());
        assertTrue(manager.recentTransactions(recipient.getId(), 12).isEmpty());
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
