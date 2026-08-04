package com.ssafy.deployengine.dto;

import java.util.List;

/**
 * query_range로 긁어온 시계열 지표 - 지표별로 (타임스탬프, 값) 포인트들의 목록.
 * 스냅샷(DeploymentMetricsResponse)이 "현재 값 하나"였다면 이건 "구간 내 추이" = 그래프용.
 */
public record DeploymentMetricsRangeResponse(
        List<MetricPoint> cpuCores,
        List<MetricPoint> memoryBytes,
        List<MetricPoint> networkReceiveBytes,
        List<MetricPoint> networkTransmitBytes,
        List<MetricPoint> requestsTotal
) {
    /** epochSeconds = Prometheus 샘플 타임스탬프(초 단위 유닉스 시간), value = 그 시점의 값. */
    public record MetricPoint(long epochSeconds, Double value) {
    }
}
