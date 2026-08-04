package com.ssafy.deployengine.service;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.ssafy.deployengine.entity.Deployment;
import com.ssafy.deployengine.entity.DeploymentMetric;
import com.ssafy.deployengine.entity.Member;
import com.ssafy.deployengine.dto.DeploymentMetricsResponse;
import com.ssafy.deployengine.repository.DeploymentMetricRepository;
import com.ssafy.deployengine.repository.DeploymentRepository;
import com.ssafy.deployengine.repository.MemberRepository;

/**
 * 현재 RUNNING인 배포들의 CPU/메모리를 주기적으로 Prometheus에서 읽어 deployment_metrics에 한 행씩 적재한다.
 * 이력이 무한정 쌓이지 않도록 하루 한 번 오래된 행을 정리한다(retention).
 */
@Component
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private final DeploymentRepository deploymentRepository;
    private final MemberRepository memberRepository;
    private final MetricsService metricsService;
    private final DeploymentMetricRepository metricRepository;

    @Value("${deploy.metrics.retention-days:7}")
    private int retentionDays;

    public MetricsCollector(DeploymentRepository deploymentRepository,
                            MemberRepository memberRepository,
                            MetricsService metricsService,
                            DeploymentMetricRepository metricRepository) {
        this.deploymentRepository = deploymentRepository;
        this.memberRepository = memberRepository;
        this.metricsService = metricsService;
        this.metricRepository = metricRepository;
    }

    // 기본 30초 간격. 수집은 Prometheus 호출뿐이라 가볍지만, 배포 수가 많아지면 간격을 늘리면 된다.
    @Scheduled(fixedDelayString = "${deploy.metrics.collect-interval-ms:30000}")
    public void collect() {
        for (Deployment deployment : deploymentRepository.findRunning()) {
            try {
                Member member = memberRepository.findById(deployment.getMemberId()).orElse(null);
                if (member == null) {
                    continue;
                }
                DeploymentMetricsResponse m = metricsService.getMetrics(
                        com.ssafy.deployengine.support.Namespaces.toNamespace(member.getTeamName()),
                        deployment.getSlug(), deployment.getInternalPort());
                // 전부 없으면(막 떠서 표본 전이거나 스크레이프 실패) 빈 행을 남기지 않는다.
                if (isAllNull(m)) {
                    continue;
                }
                metricRepository.save(new DeploymentMetric(deployment.getId(),
                        m.cpuCores(), m.memoryBytes(), m.networkReceiveBytes(),
                        m.networkTransmitBytes(), m.requestsTotal()));
            } catch (Exception e) {
                // 한 배포 수집 실패가 나머지 수집을 막지 않도록 개별적으로 잡는다.
                log.warn("메트릭 수집 실패 (deploymentId={}): {}", deployment.getId(), e.getMessage());
            }
        }
    }

    private static boolean isAllNull(DeploymentMetricsResponse m) {
        return m.cpuCores() == null && m.memoryBytes() == null
                && m.networkReceiveBytes() == null && m.networkTransmitBytes() == null
                && m.requestsTotal() == null;
    }

    // 매일 새벽 4시에 retentionDays보다 오래된 이력 삭제. 표현식은 설정으로 덮어쓸 수 있음.
    @Scheduled(cron = "${deploy.metrics.cleanup-cron:0 0 4 * * *}")
    @Transactional
    public void cleanupOldMetrics() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = metricRepository.deleteByRecordedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("오래된 메트릭 이력 {}건 정리 (cutoff={})", deleted, cutoff);
        }
    }
}
