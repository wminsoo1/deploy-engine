package com.ssafy.deployengine.dto;

public record DeploymentResponse(
        Long deploymentId,
        String status
) {
}
