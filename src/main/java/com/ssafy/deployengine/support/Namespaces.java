package com.ssafy.deployengine.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * team_name(사람이 보는 표시용 이름 - 한글/대문자/공백 등 임의 문자 포함 가능)을
 * k8s 네임스페이스 이름(RFC 1123 label: 소문자 영숫자 + '-', 시작/끝은 영숫자)으로 변환한다.
 *
 * team_name을 그대로 슬러그화하면 한글만 있는 이름은 빈 문자열이 되는 등 무효/충돌 위험이 있어서,
 * team_name의 SHA-256 앞부분을 hex로 붙인 "team-{해시}" 형태로 만든다.
 * - 결정적(deterministic): 같은 team_name → 항상 같은 네임스페이스 (팀 리소스가 한 곳에 모임)
 * - 항상 유효: 소문자 hex + 'team-' 접두사라 RFC 1123을 항상 만족
 * - 충돌 사실상 없음: 48비트(hex 12자)
 *
 * ⚠️ 네임스페이스를 유도하는 모든 경로(배포/메트릭/로그/스케일/삭제)는 반드시 이 메서드를 통해야
 * 같은 팀의 리소스가 동일 네임스페이스로 일관되게 매핑된다.
 */
public final class Namespaces {

    private Namespaces() {
    }

    public static String toNamespace(String teamName) {
        String key = teamName == null ? "" : teamName.trim();
        byte[] digest = sha256(key);
        StringBuilder hex = new StringBuilder(12);
        for (int i = 0; i < 6; i++) { // 6바이트 = hex 12자
            hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
            hex.append(Character.forDigit(digest[i] & 0xF, 16));
        }
        return "team-" + hex;
    }

    private static byte[] sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 표준 JDK에 항상 존재하므로 사실상 도달 불가.
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }
}
