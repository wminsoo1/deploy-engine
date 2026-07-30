package com.ssafy.deployengine.dto;

/** 메인 서버가 보내는 배포 요청 - deployments 테이블에 이미 만들어둔 행의 id만 받는다. */
public record DeploymentRequest(
        Long deploymentId
) {
}
