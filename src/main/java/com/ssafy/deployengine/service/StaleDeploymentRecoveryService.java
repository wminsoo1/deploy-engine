package com.ssafy.deployengine.service;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.ssafy.deployengine.entity.Deployment;
import com.ssafy.deployengine.entity.DeploymentLog;
import com.ssafy.deployengine.entity.DeploymentStatus;
import com.ssafy.deployengine.repository.DeploymentLogRepository;
import com.ssafy.deployengine.repository.DeploymentRepository;

/**
 * BUILDING/DEPLOYING에서 오래 멈춰 있는 배포를 찾아 복구한다.
 *
 * deploy-engine 프로세스가 처리 도중(kill -9, OOM, 파드 재시작 등으로) 죽으면
 * DeploymentProcessor의 catch(Exception)도 못 타서 FAILED 로그조차 안 남고,
 * deployment_logs의 최신 상태가 BUILDING/DEPLOYING에 영원히 멈춰있게 된다.
 * findPendingForProcessing은 최신 status가 PENDING인 것만 골라오므로 이 상태는
 * 정상적으로는 다시 집히지 않는다 - 그래서 이 상태를 별도로 감지해야 한다.
 *
 * Docker build/push, kubectl apply는 멱등적이라 처음부터 다시 돌려도 안전하다는
 * 전제 하에, 재시도 횟수가 남아있으면 PENDING 로그를 남겨 스케줄러가 다시 집도록
 * 하고(기존 재배포 트리거와 완전히 같은 메커니즘), 다 소진되면 FAILED로 확정한다.
 * 재시도 횟수는 "가장 최근 RUNNING/FAILED 이후" 구간만 세므로, 소진되어 FAILED로
 * 확정된 뒤 사용자가 수동으로 재배포하면 다시 처음부터 카운트된다.
 */
@Component
public class StaleDeploymentRecoveryService {

    private final DeploymentRepository deploymentRepository;
    private final DeploymentLogRepository deploymentLogRepository;
    private final DeploymentLogService logService;

    @Value("${deploy.stale-recovery.threshold-minutes}")
    private int thresholdMinutes;

    @Value("${deploy.stale-recovery.max-retries}")
    private int maxRetries;

    public StaleDeploymentRecoveryService(DeploymentRepository deploymentRepository,
                                           DeploymentLogRepository deploymentLogRepository,
                                           DeploymentLogService logService) {
        this.deploymentRepository = deploymentRepository;
        this.deploymentLogRepository = deploymentLogRepository;
        this.logService = logService;
    }

    @Scheduled(fixedDelayString = "${deploy.stale-recovery.check-interval-ms}")
    public void recoverStale() {
        for (Deployment deployment : claim()) {
            recover(deployment);
        }
    }

    // pollAndProcess()의 claim()과 동일한 이유로, 조회+선점만 짧은 트랜잭션으로 분리한다.
    @Transactional
    public List<Deployment> claim() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(thresholdMinutes);
        return deploymentRepository.findStaleForRecovery(threshold, 5);
    }

    private void recover(Deployment deployment) {
        Long id = deployment.getId();
        long attemptsInCurrentCycle = countAttemptsSinceLastTerminal(id);

        if (attemptsInCurrentCycle < maxRetries) {
            logService.append(id,
                    "정체 감지(마지막 진행 후 " + thresholdMinutes + "분 이상 응답 없음) - 자동 재시도 "
                            + (attemptsInCurrentCycle + 1) + "/" + maxRetries,
                    DeploymentStatus.PENDING);
        } else {
            logService.append(id,
                    "정체 감지 후 자동 재시도 " + maxRetries + "회를 모두 소진해 실패 처리합니다.",
                    DeploymentStatus.FAILED);
        }
    }

    /** 가장 최근 RUNNING/FAILED(있다면) 이후에 쌓인 PENDING 로그 수 = 지금 사이클의 시도 횟수. */
    private long countAttemptsSinceLastTerminal(Long deploymentId) {
        long attempts = 0;
        for (DeploymentLog entry : deploymentLogRepository.findByDeploymentIdOrderByIdAsc(deploymentId)) {
            if (entry.getStatus() == DeploymentStatus.RUNNING || entry.getStatus() == DeploymentStatus.FAILED) {
                attempts = 0;
            } else if (entry.getStatus() == DeploymentStatus.PENDING) {
                attempts++;
            }
        }
        return attempts;
    }
}
