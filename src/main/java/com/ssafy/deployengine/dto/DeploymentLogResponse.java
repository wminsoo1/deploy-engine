package com.ssafy.deployengine.dto;

import java.time.LocalDateTime;
import com.ssafy.deployengine.entity.DeploymentLog;

public record DeploymentLogResponse(
        String logMessage,
        String status,
        LocalDateTime createdAt
) {
    public static DeploymentLogResponse from(DeploymentLog log) {
        return new DeploymentLogResponse(log.getLogMessage(), log.getStatus().name(), log.getCreatedAt());
    }
}
