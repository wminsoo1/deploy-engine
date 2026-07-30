package com.ssafy.deployengine.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.ssafy.deployengine.entity.DeploymentMetric;

public interface DeploymentMetricRepository extends JpaRepository<DeploymentMetric, Long> {

    // 특정 배포의, 특정 시각 이후 이력을 오래된 순으로 (그래프용 조회).
    List<DeploymentMetric> findByDeploymentIdAndRecordedAtAfterOrderByRecordedAtAsc(
            Long deploymentId, LocalDateTime after);

    // 이력이 무한정 쌓이지 않도록 오래된 행을 일괄 삭제(retention). @Modifying으로 엔티티 로딩 없이 벌크 삭제.
    @Modifying
    @Query("DELETE FROM DeploymentMetric m WHERE m.recordedAt < :cutoff")
    int deleteByRecordedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
