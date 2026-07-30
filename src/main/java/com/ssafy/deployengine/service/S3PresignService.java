package com.ssafy.deployengine.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * S3 비공개 객체의 다운로드용 presigned URL을 생성한다.
 * 컨트롤 플레인 EC2의 IAM 역할(k3s-control-plane-role, s3:GetObject 권한)로 서명하며,
 * 무거운 AWS SDK를 JVM(t3.small, 힙 384MB)에 얹지 않으려고 aws CLI를 셸아웃한다
 * (KubernetesDeployService/DockerBuildService가 kubectl/ssh를 셸아웃하는 것과 동일한 방식).
 *
 * 생성된 URL은 자격증명이 필요 없는 서명된 HTTPS URL이라, 실제 다운로드는 AWS 자격증명이 없는
 * E206(도커 빌드가 도는 워커)에서 curl로 그대로 받을 수 있다.
 */
@Service
public class S3PresignService {

    @Value("${deploy.aws-region:ap-northeast-2}")
    private String region;

    @Value("${deploy.aws-cli-path:/usr/bin/aws}")
    private String awsCliPath;

    // 발급~다운로드 시작까지의 여유. 도커 빌드 초반에 바로 받으므로 15분이면 넉넉하다.
    @Value("${deploy.artifact.presign-expiry-seconds:900}")
    private long expirySeconds;

    public String presignGet(String bucket, String objectKey) {
        String s3Uri = "s3://" + bucket + "/" + objectKey;
        List<String> cmd = List.of(awsCliPath, "s3", "presign", s3Uri,
                "--region", region, "--expires-in", String.valueOf(expirySeconds));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        try {
            Process process = pb.start();
            String out;
            String err;
            try (var in = process.getInputStream(); var errStream = process.getErrorStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                err = new String(errStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            int code = process.waitFor();
            if (code != 0 || !out.startsWith("https://")) {
                throw new IllegalStateException("presigned URL 생성 실패 (exit=" + code + "): "
                        + (err.isBlank() ? out : err));
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("presigned URL 생성 중 인터럽트됨", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("presigned URL 생성 중 오류: " + e.getMessage(), e);
        }
    }
}
