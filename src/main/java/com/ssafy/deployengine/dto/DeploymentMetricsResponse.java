package com.ssafy.deployengine.dto;

/** cAdvisor/Traefik에서 긁어온 Prometheus 지표의 "현재 스냅샷" (폴링 방식 - 과거 추이는 없음). */
public record DeploymentMetricsResponse(
        Double cpuCores,
        Long memoryBytes,
        Long networkReceiveBytes,
        Long networkTransmitBytes,
        Long diskUsageBytes,
        Long requestsTotal
) {
}
