import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptLimiterTest {
    @Test
    void locksForThreeMinutesAfterFiveFailuresAndResetsAfterSuccessfulLogin() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-26T12:00:00Z"), ZoneId.of("UTC"));
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

        for (int attempt = 0; attempt < 4; attempt++) {
            assertFalse(limiter.recordFailure());
        }
        assertEquals(1, limiter.attemptsRemaining());
        assertTrue(limiter.recordFailure());
        assertEquals(180, limiter.secondsRemaining());
        assertTrue(limiter.recordFailure());
        assertEquals(5, limiter.attemptsRemaining());

        clock.advance(Duration.ofSeconds(179));
        assertEquals(1, limiter.secondsRemaining());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(limiter.isLockoutComplete());
        assertFalse(limiter.recordFailure());
        assertEquals(4, limiter.attemptsRemaining());
        limiter.reset();

        assertEquals(5, limiter.attemptsRemaining());
        assertEquals(0, limiter.secondsRemaining());
        assertFalse(limiter.recordFailure());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
