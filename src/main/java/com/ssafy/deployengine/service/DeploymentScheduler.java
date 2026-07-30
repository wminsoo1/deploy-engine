package com.ssafy.deployengine.service;

import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.ssafy.deployengine.entity.Deployment;
import com.ssafy.deployengine.repository.DeploymentRepository;

/**
 * 모아몽 Outbox 스케줄러와 동일한 패턴: 주기적으로 PENDING 상태를
 * FOR UPDATE SKIP LOCKED로 잠가서 가져온 뒤 실제 처리를 위임한다.
 */
@Component
public class DeploymentScheduler {

    private final DeploymentRepository deploymentRepository;
    private final DeploymentProcessor deploymentProcessor;

    public DeploymentScheduler(DeploymentRepository deploymentRepository, DeploymentProcessor deploymentProcessor) {
        this.deploymentRepository = deploymentRepository;
        this.deploymentProcessor = deploymentProcessor;
    }

    @Scheduled(fixedDelay = 5000)
    public void pollAndProcess() {
        // 잠금을 짧게만 잡기 위해 "조회+선점"만 별도의 짧은 트랜잭션으로 분리한다.
        // docker build/kubectl apply처럼 오래 걸리는 실제 작업까지 트랜잭션 안에 두면
        // FOR UPDATE SKIP LOCKED 잠금이 분 단위로 유지되어 다른 처리를 막아버리기 때문.
        List<Deployment> claimed = claim();
        for (Deployment deployment : claimed) {
            deploymentProcessor.process(deployment);
        }
    }

    @Transactional
    public List<Deployment> claim() {
        return deploymentRepository.findPendingForProcessing(5);
    }
}
