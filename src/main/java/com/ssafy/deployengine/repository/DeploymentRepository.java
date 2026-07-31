package com.ssafy.deployengine.repository;

import java.time.LocalDateTime;
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

    /**
     * 가장 최근 로그 상태가 BUILDING/DEPLOYING인데, 그 로그가 threshold보다 오래된 것들.
     * 정상적으로 처리 중이면 몇 분 안에 다음 로그(DEPLOYING/RUNNING/FAILED)가 남으므로,
     * 오래 멈춰 있다는 건 deploy-engine 프로세스가 그 사이 죽었다는 뜻이다(kill -9/OOM/파드
     * 재시작 등 - 이런 경우 DeploymentProcessor의 catch(Exception)도 못 타서 FAILED조차
     * 안 남는다). findPendingForProcessing과 동일하게 FOR UPDATE SKIP LOCKED로 잠가서
     * 여러 인스턴스가 동시에 복구를 시도하지 않게 한다.
     */
    @Query(value = "SELECT d.* FROM deployments d " +
            "JOIN (" +
            "  SELECT dl1.deployment_id, dl1.status, dl1.created_at FROM deployment_logs dl1 " +
            "  WHERE dl1.id = (SELECT MAX(dl2.id) FROM deployment_logs dl2 WHERE dl2.deployment_id = dl1.deployment_id)" +
            ") latest ON latest.deployment_id = d.id " +
            "WHERE latest.status IN ('BUILDING', 'DEPLOYING') AND latest.created_at < :threshold " +
            "ORDER BY latest.created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Deployment> findStaleForRecovery(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);
}
