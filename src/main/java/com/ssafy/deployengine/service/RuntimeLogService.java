package com.ssafy.deployengine.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 사용자 앱의 런타임 로그(stdout/stderr)를 kubectl logs로 가져온다.
 * 프론트가 이 스냅샷을 주기적으로 폴링해서 라이브 로그 화면을 그린다(메트릭과 동일한 폴링 방식).
 *
 * client-java(24.0.0)가 k3s 최신 Pod status 필드를 못 읽어 구조화된 Pod 조회가 파싱 예외로 깨지는
 * 이슈가 있어(KubernetesDeployService의 진단 로그 경로와 동일한 이유), 여기서도 client-java 대신
 * kubectl을 셸아웃한다.
 */
@Service
public class RuntimeLogService {

    @Value("${deploy.kubeconfig-path}")
    private String kubeconfigPath;

    /** 최근 tail줄을 한 번에 반환. 실패해도 예외를 던지지 않고 사유 문자열을 돌려준다(폴링이 계속 도니까). */
    public String getRecentLogs(String namespace, String appName, int tail) {
        try {
            // deployment/<app>의 <app> 컨테이너만 대상(db-proxy 같은 사이드카 로그 섞임 방지).
            // replica가 여럿이면 kubectl이 그중 한 파드를 고른다(MVP 기준 replica=1이라 무관).
            List<String> cmd = List.of(
                    "kubectl", "--kubeconfig", kubeconfigPath, "-n", namespace,
                    "logs", "deployment/" + appName, "-c", appName, "--tail=" + tail);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true); // 컨테이너 시작 대기 등 kubectl 경고도 로그 화면에 그대로 보이게
            Process p = pb.start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            p.waitFor();
            return out;
        } catch (Exception e) {
            return "(로그 조회 실패: [" + e.getClass().getSimpleName() + "] " + e.getMessage() + ")";
        }
    }
}
