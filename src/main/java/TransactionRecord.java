import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionRecord(String type, BigDecimal amount, LocalDateTime timestamp,
                                Long relatedAccountId, String relatedAccountName) { }
