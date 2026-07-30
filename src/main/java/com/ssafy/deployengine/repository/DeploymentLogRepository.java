package com.ssafy.deployengine.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.ssafy.deployengine.entity.DeploymentLog;

public interface DeploymentLogRepository extends JpaRepository<DeploymentLog, Long> {

    // created_at은 초 단위까지만 저장돼서 짧은 시간에 여러 줄이 쌓이면 순서가 꼬일 수 있음.
    // id(자동증가값)가 삽입 순서를 정확히 반영하므로 이걸로 정렬한다.
    List<DeploymentLog> findByDeploymentIdOrderByIdAsc(Long deploymentId);

    // "현재 상태" = 가장 최근에 쌓인 로그 한 줄의 status
    Optional<DeploymentLog> findTopByDeploymentIdOrderByIdDesc(Long deploymentId);
}
