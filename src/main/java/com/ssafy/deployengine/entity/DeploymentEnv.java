package com.ssafy.deployengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 배포별 커스텀 환경변수. 컬럼명은 encrypted_value지만 현재는 암호화 없이 평문으로 들어있음. */
@Entity
@Table(name = "deployment_envs")
public class DeploymentEnv {

    @Id
    private Long id;

    @Column(name = "deployment_id")
    private Long deploymentId;

    @Column(name = "env_key")
    private String envKey;

    @Column(name = "encrypted_value")
    private String value;

    protected DeploymentEnv() {
    }

    public String getEnvKey() {
        return envKey;
    }

    public String getValue() {
        return value;
    }
}
