package com.ssafy.deployengine.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.ssafy.deployengine.entity.DeploymentEnv;

public interface DeploymentEnvRepository extends JpaRepository<DeploymentEnv, Long> {
    List<DeploymentEnv> findByDeploymentId(Long deploymentId);
}
