package com.ssafy.deployengine.entity;

import java.time.LocalDateTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 깃헙 액션처럼 한 줄씩 쌓이는 배포 진행 로그.
 * deployments 테이블에는 별도 상태 컬럼이 없어서, "현재 상태"는 이 테이블에서
 * 가장 최근(id가 가장 큰) 행의 status로 판단한다.
 */
@Entity
@Table(name = "deployment_logs")
public class DeploymentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deployment_id", nullable = false)
    private Long deploymentId;

    @Column(name = "log_message", nullable = false)
    private String logMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeploymentStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected DeploymentLog() {
    }

    public DeploymentLog(Long deploymentId, String logMessage, DeploymentStatus status) {
        this.deploymentId = deploymentId;
        this.logMessage = logMessage;
        this.status = status;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getDeploymentId() {
        return deploymentId;
    }

    public String getLogMessage() {
        return logMessage;
    }

    public DeploymentStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
