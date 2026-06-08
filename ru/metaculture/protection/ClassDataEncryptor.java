package ru.metaculture.protection;

import java.security.SecureRandom;
import java.util.Locale;

public final class ClassDataEncryptor {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long DELTA = 0xD6E8FEB86659FD93L;
    private static final long SPREAD = 0xC2B2AE3D27D4EB4FL;
    private static final long TWIST = 0x94D049BB133111EBL;

    private final long[] keyWords;
    private final SecureRandom secureRandom;
    private final String normalizedKey;
    private final long keyFingerprint;

    public ClassDataEncryptor(String hexKey) {
        this.normalizedKey = normalizeKey(hexKey);
        this.keyWords = parseKey(normalizedKey);
        this.secureRandom = new SecureRandom();
        this.keyFingerprint = computeFingerprint(this.keyWords);
    }

    public EncryptionResult encrypt(byte[] plain) {
        byte[] cipher = new byte[plain.length];
        long nonce0 = secureRandom.nextLong();
        long nonce1 = secureRandom.nextLong();
        applyCipher(plain, cipher, nonce0, nonce1);
        long checksum = computeChecksum(plain);
        long maskedChecksum = checksum ^ keyWords[3] ^ nonce0;
        return new EncryptionResult(cipher, nonce0, nonce1, maskedChecksum);
    }

    private void applyCipher(byte[] plain, byte[] cipher, long nonce0, long nonce1) {
        long v0 = nonce0 ^ keyWords[0];
        long v1 = Long.rotateLeft(nonce1 ^ keyWords[1], 13);
        long v2 = keyWords[2] ^ 0x517CC1B727220A95L;
        long v3 = keyWords[3] ^ 0xA4093822299F31D0L;

        for (int i = 0; i < plain.length; i++) {
            long mix = Long.rotateLeft(v0 + v2 + (i * GOLDEN_GAMMA), 7) ^ (v1 + v3);
            long keystream = mix ^ Long.rotateLeft(v1, 11) ^ Long.rotateLeft(v2, 3);
            int keyByte = (int) ((keystream >>> 16) & 0xFFL);
            cipher[i] = (byte) (((plain[i] & 0xFF) ^ keyByte) & 0xFF);
            v0 = Long.rotateLeft(v0 ^ keystream ^ DELTA, 9) + v3;
            v1 = Long.rotateLeft(v1 + mix + SPREAD, 5) ^ v0;
            v2 ^= Long.rotateLeft(keystream + v1, 13) + TWIST;
        }
    }

    private static long computeChecksum(byte[] data) {
        long acc = 0xC3A5C85C97CB3127L;
        for (byte value : data) {
            acc ^= (value & 0xFFL) + GOLDEN_GAMMA;
            acc = Long.rotateLeft(acc, 11) + SPREAD;
            acc ^= acc >>> 7;
        }
        return acc;
    }

    public long getKeyFingerprint() {
        return keyFingerprint;
    }

    public String getNormalizedKey() {
        return normalizedKey;
    }

    public long[] getKeyWords() {
        return keyWords.clone();
    }

    public static String generateRandomKey() {
        SecureRandom random = new SecureRandom();
        byte[] buffer = new byte[32];
        random.nextBytes(buffer);
        StringBuilder builder = new StringBuilder(64);
        for (byte b : buffer) {
            builder.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
        }
        return builder.toString();
    }

    private static String normalizeKey(String input) {
        if (input == null) {
            throw new IllegalArgumentException("class data key must not be null");
        }
        StringBuilder cleaned = new StringBuilder(64);
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (Character.isWhitespace(ch) || ch == '-' || ch == ':') {
                continue;
            }
            cleaned.append(ch);
        }
        if (cleaned.length() != 64) {
            throw new IllegalArgumentException("class data key must contain exactly 64 hexadecimal characters");
        }
        String normalized = cleaned.toString().toUpperCase(Locale.ROOT);
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'F'))) {
                throw new IllegalArgumentException("class data key contains invalid characters");
            }
        }
        return normalized;
    }

    private static long[] parseKey(String normalized) {
        long[] result = new long[4];
        for (int word = 0; word < 4; word++) {
            int offset = word * 16;
            long value = 0;
            for (int i = 0; i < 16; i++) {
                value = (value << 4) | nibble(normalized.charAt(offset + i));
            }
            result[word] = value;
        }
        return result;
    }

    private static int nibble(char ch) {
        if (ch >= '0' && ch <= '9') {
            return ch - '0';
        }
        return 10 + (ch - 'A');
    }

    private static long computeFingerprint(long[] words) {
        long rotated = Long.rotateLeft(words[0] ^ words[1], 17);
        rotated ^= words[2];
        rotated ^= words[3];
        return rotated;
    }

    public static final class EncryptionResult {
        private final byte[] cipher;
        private final long nonce0;
        private final long nonce1;
        private final long maskedChecksum;

        private EncryptionResult(byte[] cipher, long nonce0, long nonce1, long maskedChecksum) {
            this.cipher = cipher;
            this.nonce0 = nonce0;
            this.nonce1 = nonce1;
            this.maskedChecksum = maskedChecksum;
        }

        public byte[] getCipher() {
            return cipher;
        }

        public long getNonce0() {
            return nonce0;
        }

        public long getNonce1() {
            return nonce1;
        }

        public long getMaskedChecksum() {
            return maskedChecksum;
        }
    }
}
