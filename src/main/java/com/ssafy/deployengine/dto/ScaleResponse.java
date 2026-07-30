package com.ssafy.deployengine.dto;

/** 스케일 결과 - 반영된 replica 수. */
public record ScaleResponse(
        Long deploymentId,
        int replicas
) {
}
