package com.ssafy.deployengine.entity;

/** 백엔드팀의 deployment_logs.status enum과 정확히 일치해야 한다. */
public enum DeploymentStatus {
    PENDING,
    BUILDING,
    DEPLOYING,
    RUNNING,
    FAILED
}
