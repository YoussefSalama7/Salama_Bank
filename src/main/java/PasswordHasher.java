import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

final class PasswordHasher {
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() { }

    static String newSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    static String hash(char[] password, String salt) {
        PBEKeySpec spec = new PBEKeySpec(password, Base64.getDecoder().decode(salt), ITERATIONS, KEY_LENGTH);
        try {
            byte[] encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(encoded);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Password hashing is unavailable on this Java runtime.", e);
        } finally {
            spec.clearPassword();
        }
    }

    static boolean verify(char[] attempt, String salt, String expectedHash) {
        byte[] actual = Base64.getDecoder().decode(hash(attempt, salt));
        byte[] expected = Base64.getDecoder().decode(expectedHash);
        if (actual.length != expected.length) return false;
        int difference = 0;
        for (int i = 0; i < actual.length; i++) difference |= actual[i] ^ expected[i];
        return difference == 0;
    }
}
