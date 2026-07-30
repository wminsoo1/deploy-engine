-- deployment_metrics: 배포별 리소스 사용량 시계열 이력 (deploy-engine 소유 테이블).
-- 메트릭 API(/metrics)가 노출하는 6개 지표를 그대로 저장: CPU, 메모리, 네트워크 수신/송신, 디스크, 요청수.
-- ddl-auto=validate라 애플리케이션이 자동 생성하지 않는다. 새 환경에 배포하기 전에 이 DDL을 먼저 실행할 것.
-- (컬럼 타입/이름은 DeploymentMetric 엔티티와 정확히 일치해야 validate가 통과한다.)
CREATE TABLE IF NOT EXISTS deployment_metrics (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    deployment_id          BIGINT       NOT NULL,
    cpu_cores              DOUBLE       NULL,
    memory_bytes           BIGINT       NULL,
    network_receive_bytes  BIGINT       NULL,
    network_transmit_bytes BIGINT       NULL,
    disk_usage_bytes       BIGINT       NULL,
    requests_total         BIGINT       NULL,
    recorded_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_deployment_recorded (deployment_id, recorded_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
