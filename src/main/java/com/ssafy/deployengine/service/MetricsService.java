package com.ssafy.deployengine.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.ssafy.deployengine.dto.DeploymentMetricsRangeResponse;
import com.ssafy.deployengine.dto.DeploymentMetricsRangeResponse.MetricPoint;
import com.ssafy.deployengine.dto.DeploymentMetricsResponse;

/**
 * Prometheus(E206, NetworkPolicy로 컨트롤 플레인 IP만 허용)에 PromQL로 물어봐서
 * CPU/메모리/네트워크(cAdvisor)와 요청수(Traefik)를 가져온다.
 * - getMetrics: /api/v1/query    → "현재 값" 스냅샷 하나
 * - getMetricsRange: /api/v1/query_range → 구간 내 시계열(그래프용)
 * 두 경로가 같은 PromQL을 쓰도록 쿼리 문자열은 아래 *Query() 메서드로 한 곳에서 만든다.
 */
@Service
public class MetricsService {

    @Value("${deploy.prometheus-url}")
    private String prometheusUrl;

    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeploymentMetricsResponse getMetrics(String namespace, String appName, int port) {
        return new DeploymentMetricsResponse(
                queryScalar(cpuQuery(namespace, appName)),
                queryScalarAsLong(memoryQuery(namespace, appName)),
                queryScalarAsLong(netRxQuery(namespace, appName)),
                queryScalarAsLong(netTxQuery(namespace, appName)),
                queryScalarAsLong(requestsQuery(namespace, appName, port)));
    }

    /**
     * 최근 rangeSeconds 동안의 추이를, stepSeconds 간격 샘플로 반환한다.
     * (예: rangeSeconds=1800, stepSeconds=15 → 최근 30분을 15초 간격 = 약 120포인트)
     */
    public DeploymentMetricsRangeResponse getMetricsRange(String namespace, String appName, int port,
                                                          long rangeSeconds, long stepSeconds) {
        long end = Instant.now().getEpochSecond();
        long start = end - rangeSeconds;
        return new DeploymentMetricsRangeResponse(
                queryRange(cpuQuery(namespace, appName), start, end, stepSeconds),
                queryRange(memoryQuery(namespace, appName), start, end, stepSeconds),
                queryRange(netRxQuery(namespace, appName), start, end, stepSeconds),
                queryRange(netTxQuery(namespace, appName), start, end, stepSeconds),
                queryRange(requestsQuery(namespace, appName, port), start, end, stepSeconds));
    }

    // --- PromQL 조립 (스냅샷/시계열 공용) ---------------------------------------

    // 파드 이름은 "appName-<replicaset해시>-<파드해시>" 형태라, prefix가 겹치는 다른
    // 배포(예: engine-test vs engine-test-hardcoded)와 섞이지 않도록 세그먼트 수를 고정해서 매칭.
    private String podRegex(String appName) {
        return "^" + appName + "-[^-]+-[^-]+$";
    }

    private String cpuQuery(String namespace, String appName) {
        return String.format(
                "sum(rate(container_cpu_usage_seconds_total{namespace=\"%s\",pod=~\"%s\",container=\"%s\"}[2m]))",
                namespace, podRegex(appName), appName);
    }

    private String memoryQuery(String namespace, String appName) {
        return String.format(
                "sum(container_memory_working_set_bytes{namespace=\"%s\",pod=~\"%s\",container=\"%s\"})",
                namespace, podRegex(appName), appName);
    }

    private String netRxQuery(String namespace, String appName) {
        return String.format(
                "sum(container_network_receive_bytes_total{namespace=\"%s\",pod=~\"%s\"})",
                namespace, podRegex(appName));
    }

    private String netTxQuery(String namespace, String appName) {
        return String.format(
                "sum(container_network_transmit_bytes_total{namespace=\"%s\",pod=~\"%s\"})",
                namespace, podRegex(appName));
    }

    private String requestsQuery(String namespace, String appName, int port) {
        // Traefik의 서비스 이름은 "<namespace>-<appName>-<port>@kubernetes" 형식으로 고정돼 있음
        // (실제 쿼리로 확인함) - regex 없이 정확히 매칭 가능.
        String traefikService = namespace + "-" + appName + "-" + port + "@kubernetes";
        return String.format("sum(traefik_service_requests_total{service=\"%s\"})", traefikService);
    }

    // --- Prometheus 호출 -----------------------------------------------------

    private Long queryScalarAsLong(String promql) {
        Double value = queryScalar(promql);
        return value != null ? Math.round(value) : null;
    }

    private Double queryScalar(String promql) {
        String url = prometheusUrl + "/api/v1/query?query=" + enc(promql);
        try {
            JsonNode result = fetch(url).path("data").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return null; // 아직 해당 Pod의 지표가 안 쌓였거나(막 배포됨) 스크레이프 전
            }
            // result[0].value = [타임스탬프, "값(문자열)"]
            return Double.parseDouble(result.get(0).path("value").get(1).asText());
        } catch (Exception e) {
            // 메트릭 조회 실패가 배포 자체를 실패시키면 안 되므로, 여기서는 null로 처리하고
            // 호출한 쪽(컨트롤러)에서 "값 없음"으로 응답한다.
            return null;
        }
    }

    private List<MetricPoint> queryRange(String promql, long start, long end, long step) {
        String url = prometheusUrl + "/api/v1/query_range?query=" + enc(promql)
                + "&start=" + start + "&end=" + end + "&step=" + step;
        List<MetricPoint> points = new ArrayList<>();
        try {
            JsonNode result = fetch(url).path("data").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return points; // 구간 내 샘플이 아직 없음 - 빈 배열 (그래프에 점이 안 찍힘)
            }
            // sum()으로 묶여서 시리즈는 하나. result[0].values = [[ts, "값"], [ts, "값"], ...]
            for (JsonNode sample : result.get(0).path("values")) {
                long ts = sample.get(0).asLong();
                Double value = parseOrNull(sample.get(1).asText());
                points.add(new MetricPoint(ts, value));
            }
            return points;
        } catch (Exception e) {
            return points; // 스냅샷과 동일하게, 실패는 "값 없음"으로 처리
        }
    }

    private JsonNode fetch(String url) throws Exception {
        // 이미 완성·인코딩된 URL이므로 URI.create로 넘겨 RestClient가 재인코딩(이중 인코딩)하지 않게 한다.
        // (문자열로 넘기면 URI 템플릿으로 재처리돼 %28 등이 %2528로 깨져 Prometheus가 빈 결과를 준다.)
        String body = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
        return objectMapper.readTree(body);
    }

    private String enc(String promql) {
        return URLEncoder.encode(promql, StandardCharsets.UTF_8);
    }

    private Double parseOrNull(String s) {
        // Prometheus가 결측/무한을 "NaN"/"+Inf" 문자열로 줄 수 있어 방어적으로 파싱.
        try {
            double d = Double.parseDouble(s);
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
