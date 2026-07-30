package com.ssafy.deployengine.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 백엔드(ssafyhub)가 SecretEncryptor로 암호화해 DB에 저장한 값(비밀번호/환경변수)을 복호화한다.
 * 백엔드와 정확히 같은 방식이어야 한다:
 *   - AES-256-GCM (NoPadding), 12바이트 IV, 128비트 태그
 *   - 저장 형식: "v1:{base64(IV)}:{base64(암호문+태그)}"
 *   - 키: 백엔드와 동일한 32바이트(Base64) 대칭키 (deploy.secret-encryption-key)
 *
 * 키가 설정 안 됐거나 값이 "v1:" 접두사가 아니면(구버전 평문) 원본을 그대로 돌려준다.
 */
@Component
public class SecretDecryptor {

    private static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey secretKey; // 키 미설정 시 null

    public SecretDecryptor(@Value("${deploy.secret-encryption-key:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            this.secretKey = null;
            return;
        }
        byte[] keyBytes = Base64.getDecoder().decode(encodedKey.trim());
        if (keyBytes.length != 32) {
            throw new IllegalStateException("deploy.secret-encryption-key는 Base64 디코딩 시 정확히 32바이트여야 함");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /** 암호화된 값이면 복호화, 평문(v1: 접두사 없음)이면 그대로 반환. */
    public String decrypt(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return value; // 평문(구버전 호환) 또는 null
        }
        if (secretKey == null) {
            throw new IllegalStateException(
                    "암호화된 값(v1:)인데 복호화 키가 없음 - deploy.secret-encryption-key(SECRET_ENCRYPTION_KEY)를 설정해야 함");
        }
        // 형식: v1:{ivB64}:{ctB64}
        String[] parts = value.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalStateException("암호문 형식이 잘못됨(v1:iv:ct 아님)");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] cipherText = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("비밀값 복호화 실패: " + e.getMessage(), e);
        }
    }
}
