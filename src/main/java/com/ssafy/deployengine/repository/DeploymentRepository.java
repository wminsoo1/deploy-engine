package com.ssafy.deployengine.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.ssafy.deployengine.entity.Deployment;

public interface DeploymentRepository extends JpaRepository<Deployment, Long> {

    /**
     * deployments 테이블에는 상태 컬럼이 없어서, 각 deployment의 가장 최근
     * deployment_logs.status가 PENDING인 것들을 잠금과 함께 가져온다.
     * FOR UPDATE SKIP LOCKED로, 여러 인스턴스가 동시에 폴링해도
     * 같은 레코드를 중복 처리하지 않는다 (모아몽 Outbox 스케줄러와 동일한 패턴).
     */
    @Query(value = "SELECT d.* FROM deployments d " +
            "JOIN (" +
            "  SELECT dl1.deployment_id, dl1.status FROM deployment_logs dl1 " +
            "  WHERE dl1.id = (SELECT MAX(dl2.id) FROM deployment_logs dl2 WHERE dl2.deployment_id = dl1.deployment_id)" +
            ") latest ON latest.deployment_id = d.id " +
            "WHERE latest.status = 'PENDING' " +
            "ORDER BY d.requested_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Deployment> findPendingForProcessing(@Param("limit") int limit);

    /**
     * 가장 최근 로그 상태가 RUNNING인 배포들(= 지금 실제로 떠 있는 것). 메트릭 수집 대상.
     * findPendingForProcessing과 같은 "최신 로그 상태" 판정을 쓰되, 잠금/LIMIT 없이 전부 가져온다.
     */
    @Query(value = "SELECT d.* FROM deployments d " +
            "JOIN (" +
            "  SELECT dl1.deployment_id, dl1.status FROM deployment_logs dl1 " +
            "  WHERE dl1.id = (SELECT MAX(dl2.id) FROM deployment_logs dl2 WHERE dl2.deployment_id = dl1.deployment_id)" +
            ") latest ON latest.deployment_id = d.id " +
            "WHERE latest.status = 'RUNNING'",
            nativeQuery = true)
    List<Deployment> findRunning();
}
