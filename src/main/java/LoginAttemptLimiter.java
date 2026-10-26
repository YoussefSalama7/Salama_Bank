import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

final class LoginAttemptLimiter {
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_SECONDS = 3 * 60;

    private final Clock clock;
    private int failedAttempts;
    private Instant lockedUntil;

    LoginAttemptLimiter() {
        this(Clock.systemDefaultZone());
    }

    LoginAttemptLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    boolean recordFailure() {
        if (secondsRemaining() > 0) return true;
        failedAttempts++;
        if (failedAttempts < MAX_ATTEMPTS) return false;
        failedAttempts = 0;
        lockedUntil = clock.instant().plusSeconds(LOCKOUT_SECONDS);
        return true;
    }

    int attemptsRemaining() {
        return MAX_ATTEMPTS - failedAttempts;
    }

    long secondsRemaining() {
        if (lockedUntil == null) return 0;
        long milliseconds = Duration.between(clock.instant(), lockedUntil).toMillis();
        return Math.max(0, (milliseconds + 999) / 1000);
    }

    boolean isLockoutComplete() {
        return lockedUntil != null && secondsRemaining() == 0;
    }

    void reset() {
        failedAttempts = 0;
        lockedUntil = null;
    }
}
