package com.ssafy.deployengine.entity;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 배포별 리소스 사용량의 시계열 이력. 수집기(MetricsCollector)가 주기적으로 Prometheus에서
 * 현재값을 읽어 한 행씩 append한다. 메트릭 API(/metrics)가 노출하는 지표를 그대로 저장한다:
 * CPU, 메모리, 네트워크 수신/송신, 요청수.
 *
 * deployments/deployment_logs 등과 달리 이 테이블은 "우리 소유"다(백엔드 스키마 아님).
 * ddl-auto=validate라 자동 생성되지 않으므로 테이블을 미리 만들어 둬야 한다(DDL: db/deployment_metrics.sql).
 * 각 값은 Prometheus에 아직 표본이 없으면(막 배포됨/스크레이프 전) null일 수 있다.
 *
 * disk_usage_bytes 컬럼은 더 이상 안 쓴다(사용자 요청으로 지표 제거) - DB 컬럼 자체는
 * nullable이라 남겨둬도 무해하고, ddl-auto=validate 모드에서 엔티티가 매핑 안 하는 컬럼이
 * 테이블에 남아있는 건 문제가 안 된다.
 */
@Entity
@Table(name = "deployment_metrics")
public class DeploymentMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;

    // CPU 코어 사용량(예: 0.03 = 30 millicore)
    @Column(name = "cpu_cores")
    private Double cpuCores;

    // 메모리 working set 바이트
    @Column(name = "memory_bytes")
    private Long memoryBytes;

    // 네트워크 누적 수신/송신 바이트 (누적 카운터)
    @Column(name = "network_receive_bytes")
    private Long networkReceiveBytes;

    @Column(name = "network_transmit_bytes")
    private Long networkTransmitBytes;

    // Traefik 기준 누적 요청수 (누적 카운터)
    @Column(name = "requests_total")
    private Long requestsTotal;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    protected DeploymentMetric() {
    }

    public DeploymentMetric(Long deploymentId, Double cpuCores, Long memoryBytes,
                            Long networkReceiveBytes, Long networkTransmitBytes,
                            Long requestsTotal) {
        this.deploymentId = deploymentId;
        this.cpuCores = cpuCores;
        this.memoryBytes = memoryBytes;
        this.networkReceiveBytes = networkReceiveBytes;
        this.networkTransmitBytes = networkTransmitBytes;
        this.requestsTotal = requestsTotal;
        this.recordedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getDeploymentId() {
        return deploymentId;
    }

    public Double getCpuCores() {
        return cpuCores;
    }

    public Long getMemoryBytes() {
        return memoryBytes;
    }

    public Long getNetworkReceiveBytes() {
        return networkReceiveBytes;
    }

    public Long getNetworkTransmitBytes() {
        return networkTransmitBytes;
    }

    public Long getRequestsTotal() {
        return requestsTotal;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }
}
