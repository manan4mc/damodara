package com.damodar.auth;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TokenCrypto {

    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int ITERATIONS = 600_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private TokenCrypto() {}

    public static String encrypt(String plaintext, String secretKeyOrPassphrase) throws Exception {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(salt);

        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(iv);

        SecretKey key = deriveKey(secretKeyOrPassphrase.toCharArray(), salt);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);

        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        Base64.Encoder encoder = Base64.getEncoder();
        return "{\n"
                + "  \"version\": 1,\n"
                + "  \"kdf\": \"" + KDF_ALGORITHM + "\",\n"
                + "  \"iterations\": " + ITERATIONS + ",\n"
                + "  \"salt\": \"" + encoder.encodeToString(salt) + "\",\n"
                + "  \"iv\": \"" + encoder.encodeToString(iv) + "\",\n"
                + "  \"ciphertext\": \"" + encoder.encodeToString(ciphertext) + "\"\n"
                + "}\n";
    }

    public static String decrypt(String envelopeJson, String secretKeyOrPassphrase) throws Exception {
        String saltB64 = extractField(envelopeJson, "salt");
        String ivB64 = extractField(envelopeJson, "iv");
        String ciphertextB64 = extractField(envelopeJson, "ciphertext");

        if (saltB64 == null || ivB64 == null || ciphertextB64 == null) {
            throw new IllegalArgumentException("Encrypted envelope is missing required fields (salt, iv, or ciphertext).");
        }

        Base64.Decoder decoder = Base64.getDecoder();
        byte[] salt = decoder.decode(saltB64);
        byte[] iv = decoder.decode(ivB64);
        byte[] ciphertext = decoder.decode(ciphertextB64);

        SecretKey key = deriveKey(secretKeyOrPassphrase.toCharArray(), salt);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);

        byte[] plaintextBytes = cipher.doFinal(ciphertext);
        return new String(plaintextBytes, StandardCharsets.UTF_8);
    }

    private static SecretKey deriveKey(char[] passphrase, byte[] salt) throws NoSuchAlgorithmException, InvalidKeySpecException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
        KeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, KEY_LENGTH_BITS);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static String extractField(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"?([^\",}\\]]+)\"?");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
}
