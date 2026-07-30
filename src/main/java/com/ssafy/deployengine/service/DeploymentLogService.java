package com.ssafy.deployengine.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.ssafy.deployengine.entity.DeploymentLog;
import com.ssafy.deployengine.entity.DeploymentStatus;
import com.ssafy.deployengine.repository.DeploymentLogRepository;

/**
 * 로그 한 줄을 그때그때 즉시 커밋하기 위한 서비스.
 * REQUIRES_NEW로 별도 트랜잭션에 커밋해야, 배포 처리 중간에 실패하더라도
 * 그때까지 쌓인 로그가 롤백되지 않고 프론트에 그대로 보인다.
 */
@Service
public class DeploymentLogService {

    private final DeploymentLogRepository deploymentLogRepository;

    public DeploymentLogService(DeploymentLogRepository deploymentLogRepository) {
        this.deploymentLogRepository = deploymentLogRepository;
    }

    // log_message는 TEXT라 여유가 크지만, 파드 로그/스택트레이스처럼 길이를 예측할 수 없는
    // 원본 텍스트를 그대로 남기는 경로가 있어서 그래도 안전장치로 상한을 둔다.
    private static final int MAX_MESSAGE_LENGTH = 60000;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(Long deploymentId, String message, DeploymentStatus status) {
        if (message != null && message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
        }
        deploymentLogRepository.save(new DeploymentLog(deploymentId, message, status));
    }
}
