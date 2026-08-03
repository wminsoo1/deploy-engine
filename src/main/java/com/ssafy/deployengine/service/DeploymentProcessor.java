package com.ssafy.deployengine.service;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.ssafy.deployengine.entity.Artifact;
import com.ssafy.deployengine.entity.Deployment;
import com.ssafy.deployengine.entity.DeploymentEnv;
import com.ssafy.deployengine.entity.DeploymentStatus;
import com.ssafy.deployengine.entity.Member;
import com.ssafy.deployengine.repository.ArtifactRepository;
import com.ssafy.deployengine.repository.DeploymentEnvRepository;
import com.ssafy.deployengine.repository.DeploymentRepository;
import com.ssafy.deployengine.repository.MemberRepository;
import com.ssafy.deployengine.support.SecretDecryptor;

/**
 * 배포 요청 하나를 실제로 처리하는 오케스트레이터.
 * PENDING -> BUILDING -> DEPLOYING -> RUNNING/FAILED
 * 각 단계 진입/완료마다 DeploymentLog에 한 줄씩 남긴다 (깃헙 액션 로그처럼 보이게).
 * deployments 테이블엔 상태 컬럼이 없어서, 로그 한 줄 한 줄에 그 시점의 status를 같이 찍는다.
 */
@Service
public class DeploymentProcessor {

    // deployments 테이블에 replicas/memory_limit 컬럼이 없어서 고정값 사용
    private static final int DEFAULT_REPLICAS = 1;
    private static final String DEFAULT_MEMORY_LIMIT = "512Mi";
    // deployments 테이블에 DB 계정명 컬럼이 없어서 공용 계정 사용 (비밀번호만 개별 관리)
    private static final String DB_USERNAME = "root";

    @Value("${deploy.external-base-url}")
    private String externalBaseUrl;

    private final DeploymentRepository deploymentRepository;
    private final MemberRepository memberRepository;
    private final ArtifactRepository artifactRepository;
    private final DeploymentEnvRepository deploymentEnvRepository;
    private final DeploymentLogService logService;
    private final DockerBuildService dockerBuildService;
    private final KubernetesDeployService kubernetesDeployService;
    private final S3PresignService s3PresignService;
    private final SecretDecryptor secretDecryptor;
    private final FrontendDeployService frontendDeployService;

    public DeploymentProcessor(DeploymentRepository deploymentRepository,
                                MemberRepository memberRepository,
                                ArtifactRepository artifactRepository,
                                DeploymentEnvRepository deploymentEnvRepository,
                                DeploymentLogService logService,
                                DockerBuildService dockerBuildService,
                                KubernetesDeployService kubernetesDeployService,
                                S3PresignService s3PresignService,
                                SecretDecryptor secretDecryptor,
                                FrontendDeployService frontendDeployService) {
        this.deploymentRepository = deploymentRepository;
        this.memberRepository = memberRepository;
        this.artifactRepository = artifactRepository;
        this.deploymentEnvRepository = deploymentEnvRepository;
        this.logService = logService;
        this.dockerBuildService = dockerBuildService;
        this.kubernetesDeployService = kubernetesDeployService;
        this.s3PresignService = s3PresignService;
        this.secretDecryptor = secretDecryptor;
        this.frontendDeployService = frontendDeployService;
    }

    public void process(Deployment deployment) {
        Long id = deployment.getId();
        // 로그 한 줄 한 줄에 "그 시점의 상태"를 같이 찍어야 해서, 현재 상태를 들고 있다가
        // log.line()이 호출될 때마다 참조한다.
        StatusHolder currentStatus = new StatusHolder(DeploymentStatus.PENDING);
        DockerBuildService.LogSink log = message -> logService.append(id, message, currentStatus.value);
        // 로컬(컨트롤 플레인)에 다운로드한 jar/zip/schema.sql 등이 쌓이는 작업 디렉터리 - try/catch
        // 결과와 무관하게 finally에서 항상 지워야 해서 try 밖에서 미리 정해둔다.
        String workDir = "/tmp/deploy-" + id;

        try {
            Member member = memberRepository.findById(deployment.getMemberId())
                    .orElseThrow(() -> new IllegalStateException("존재하지 않는 memberId: " + deployment.getMemberId()));

            String namespace = com.ssafy.deployengine.support.Namespaces.toNamespace(member.getTeamName());
            String appName = deployment.getSlug();
            String imageTag = appName + ":" + id;

            boolean hasBackend = deployment.getBackendArtifactId() != null;
            boolean hasFrontend = deployment.getFrontendArtifactId() != null;
            log.line("배포 시작 (member=" + namespace + ", slug=" + appName
                    + ", 백엔드=" + hasBackend + ", 프론트=" + hasFrontend + ")");

            currentStatus.value = DeploymentStatus.BUILDING;
            log.line("상태 변경: BUILDING");

            if (hasBackend) {
                Artifact artifact = artifactRepository.findById(deployment.getBackendArtifactId())
                        .orElseThrow(() -> new IllegalStateException(
                                "존재하지 않는 backendArtifactId: " + deployment.getBackendArtifactId()));
                String fileUrl = resolveArtifactDownloadUrl(artifact);

                dockerBuildService.buildImage(workDir, fileUrl, imageTag, deployment.getEffectiveTechStack(),
                        deployment.getRuntimeVersion(), deployment.getInternalPort(), log);
                dockerBuildService.transferImage(workDir, imageTag, log);
                log.line("이미지 빌드/전달 완료: " + imageTag);
            }

            currentStatus.value = DeploymentStatus.DEPLOYING;
            log.line("상태 변경: DEPLOYING");

            if (hasBackend) {
                kubernetesDeployService.ensureNamespace(namespace);
                log.line("네임스페이스 준비: " + namespace);

                Map<String, String> env = new HashMap<>();

                // 서브도메인 프록시(SubdomainProxyFilter)가 X-Forwarded-Host/Proto를 실제 공개
                // 주소로 채워서 넘겨줘도, Spring Boot는 이 설정이 켜져 있어야 그 헤더를 신뢰해서
                // 리다이렉트/절대 URL을 "13.124.149.32" 같은 내부 주소가 아니라 진짜 공개 주소로
                // 만든다. DB 사용 여부와 무관하게 모든 Spring Boot 배포에 적용한다.
                if ("SPRING_BOOT".equals(deployment.getEffectiveTechStack())) {
                    env.put("SERVER_FORWARD_HEADERS_STRATEGY", "framework");
                }

                String databaseEngine = deployment.getEffectiveDatabaseEngine();
                if (databaseEngine != null) {
                    // 비밀번호는 백엔드가 AES-GCM으로 암호화(v1:)해 저장하므로 복호화해서 실제 값을 쓴다.
                    String dbPassword = secretDecryptor.decrypt(deployment.getDatabasePassword());
                    String dbName = deployment.getDatabaseName();

                    // 이 배포 전용 MySQL이 없으면 만들고(도커 컴포즈로 db를 함께 띄우는 것과 비슷),
                    // 접속 가능해질 때까지 기다린 뒤 앱을 올려야 앱이 startup에서 DB 연결 실패로 죽지 않는다.
                    // 팀(네임스페이스) 공유가 아니라 배포마다 독립된 인스턴스라 다른 사람/다른 프로젝트와
                    // 비밀번호나 데이터가 섞이지 않는다.
                    String mysqlHost = appName + "-mysql";
                    log.line("MySQL 준비 중 (db=" + dbName + ")");
                    kubernetesDeployService.ensureMysql(namespace, appName, dbName, dbPassword);
                    boolean dbReady = kubernetesDeployService.waitForRollout(namespace, mysqlHost, 1, 120, log);
                    if (!dbReady) {
                        throw new IllegalStateException("MySQL이 제한시간 내에 준비되지 않음");
                    }
                    log.line("MySQL 준비 완료");

                    // 같은 프로젝트를 삭제 없이 재배포하면서 비밀번호만 바꾸면, 이미 떠 있는 MySQL은
                    // 예전 비밀번호 그대로라 앱이 새 비밀번호로 접속을 못 한다. 앱 컨테이너가 크래시
                    // 루프를 돌며 3분 타임아웃을 다 채우기 전에, 여기서 미리 확인해서 몇 초 안에
                    // 명확한 이유로 실패시킨다.
                    if (!kubernetesDeployService.verifyMysqlCredentials(namespace, appName, dbPassword)) {
                        throw new IllegalStateException(
                                "MySQL 비밀번호가 올바르지 않습니다. 이 프로젝트의 DB는 처음 생성할 때 설정한 " +
                                        "비밀번호로 고정되며, 재배포 시 비밀번호만 바꿔도 반영되지 않습니다. " +
                                        "비밀번호를 바꾸려면 배포를 삭제한 뒤 다시 생성해주세요.");
                    }

                    // 플랫폼 기본 이름(DB_HOST 등)을 그대로 쓰거나, 사용자가 자기 앱이 기대하는
                    // 이름을 직접 지정했으면(dbHostEnvKey 등) 그 이름으로 주입한다 - 앱마다
                    // 관례가 달라서(DB_USERNAME/DB_USER/DBUSER 등) 어떤 이름이든 결국 사용자가
                    // 자기 앱에 맞게 지정하면 확실히 매칭된다.
                    env.put(deployment.getEffectiveDbHostEnvKey(), mysqlHost);
                    env.put(deployment.getEffectiveDbPortEnvKey(), "3306");
                    env.put(deployment.getEffectiveDbNameEnvKey(), dbName);
                    env.put(deployment.getEffectiveDbUsernameEnvKey(), DB_USERNAME);
                    // Node/Express+Sequelize 등 여러 생태계에서 DB_USERNAME 대신 DB_USER를 관례로
                    // 쓴다(실제로 Sequelize 프로젝트가 DB_USER만 읽어서 빈 사용자로 접속 시도하다
                    // Access denied가 났다). 커스텀 이름을 지정 안 했으면 이 흔한 이름도 같이 준다.
                    env.put("DB_USER", DB_USERNAME);
                    env.put(deployment.getEffectiveDbPasswordEnvKey(), dbPassword);

                    // DB_HOST 계열은 이 플랫폼만의 컨벤션이라, 로컬 개발용 application.yml에
                    // spring.datasource.*를 그대로 두고 컨테이너에서만 값을 바꾸려는 흔한 Spring
                    // Boot 앱(예: url: ${SPRING_DATASOURCE_URL})은 이 값을 모르면 그대로 죽는다.
                    // Spring Boot 표준 환경변수 이름(SPRING_DATASOURCE_URL 등 - relaxed binding으로
                    // spring.datasource.*에 자동 매핑됨)도 같이 주입해서, 사용자가 이 플랫폼의
                    // 컨벤션을 몰라도 동작하게 한다.
                    if ("SPRING_BOOT".equals(deployment.getEffectiveTechStack())) {
                        env.put("SPRING_DATASOURCE_URL", "jdbc:mysql://" + mysqlHost + ":3306/" + dbName
                                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul"
                                + "&characterEncoding=UTF-8");
                        env.put("SPRING_DATASOURCE_USERNAME", DB_USERNAME);
                        env.put("SPRING_DATASOURCE_PASSWORD", dbPassword);
                    }

                    // MyBatis 등 자동 테이블 생성이 안 되는 프레임워크를 위한 선택적 DB 초기화 SQL.
                    if (deployment.getSchemaArtifactId() != null) {
                        log.line("DB 초기화 SQL 실행 중");
                        Artifact schemaArtifact = artifactRepository.findById(deployment.getSchemaArtifactId())
                                .orElseThrow(() -> new IllegalStateException(
                                        "존재하지 않는 schemaArtifactId: " + deployment.getSchemaArtifactId()));
                        String schemaFileUrl = resolveArtifactDownloadUrl(schemaArtifact);
                        java.nio.file.Path schemaFile = java.nio.file.Path.of(workDir, "schema.sql");
                        java.nio.file.Files.createDirectories(java.nio.file.Path.of(workDir));
                        dockerBuildService.downloadFile(schemaFileUrl, schemaFile);
                        kubernetesDeployService.runSchemaSql(
                                namespace, appName, databaseEngine, dbName, dbPassword, schemaFile);
                        log.line("DB 초기화 SQL 실행 완료");
                    }
                }

                if (deployment.getNeededServices().contains("REDIS")) {
                    String redisHost = appName + "-redis";
                    log.line("Redis 준비 중");
                    kubernetesDeployService.ensureRedis(namespace, appName);
                    boolean redisReady = kubernetesDeployService.waitForRollout(namespace, redisHost, 1, 60, log);
                    if (!redisReady) {
                        throw new IllegalStateException("Redis가 제한시간 내에 준비되지 않음");
                    }
                    log.line("Redis 준비 완료");
                    env.put("REDIS_HOST", redisHost);
                    env.put("REDIS_PORT", "6379");
                }

                if (deployment.getNeededServices().contains("MONGODB")) {
                    String mongoHost = appName + "-mongo";
                    log.line("MongoDB 준비 중");
                    kubernetesDeployService.ensureMongo(namespace, appName);
                    boolean mongoReady = kubernetesDeployService.waitForRollout(namespace, mongoHost, 1, 60, log);
                    if (!mongoReady) {
                        throw new IllegalStateException("MongoDB가 제한시간 내에 준비되지 않음");
                    }
                    log.line("MongoDB 준비 완료");
                    env.put("MONGO_HOST", mongoHost);
                    env.put("MONGO_PORT", "27017");
                }

                for (DeploymentEnv e : deploymentEnvRepository.findByDeploymentId(id)) {
                    // 환경변수 값도 암호화(v1:)돼 저장되므로 복호화해서 주입한다.
                    env.put(e.getEnvKey(), secretDecryptor.decrypt(e.getValue()));
                }

                kubernetesDeployService.applyDeployment(namespace, appName, imageTag,
                        deployment.getInternalPort(), DEFAULT_REPLICAS, DEFAULT_MEMORY_LIMIT, env,
                        databaseEngine);
                kubernetesDeployService.applyService(namespace, appName, deployment.getInternalPort());
                kubernetesDeployService.applyIngress(namespace, appName, deployment.getInternalPort());
                log.line("Deployment/Service/Ingress 적용 완료 (/" + namespace + "/" + appName + ")");

                // 메인 서버(SubdomainProxyFilter)를 거치지 않고 Traefik에서 바로 이 배포로 가는
                // Host 기반 라우트도 함께 만들어둔다. DNS가 아직 메인 서버를 가리키는 동안은
                // 안 쓰이는 경로라 기존 트래픽에 영향 없음 - DNS 전환 전 미리 준비해두는 것.
                if (hasFrontend) {
                    kubernetesDeployService.ensureFrontendExternalService(namespace);
                }
                kubernetesDeployService.applyHostRoute(namespace, appName, deployment.getInternalPort(), hasFrontend);
                log.line("Host 기반 라우트 적용 완료 (slug=" + appName + ", 분리형여부=" + hasFrontend + ")");
            }

            // 프론트 배포. 백엔드가 있으면 best-effort(실패해도 백엔드는 계속 진행) - 백엔드가
            // 없는(프론트만) 배포는 이게 배포의 전부라 실패하면 이 배포 자체가 실패한 것이다.
            if (hasFrontend) {
                try {
                    Artifact frontendArtifact = artifactRepository.findById(deployment.getFrontendArtifactId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "존재하지 않는 frontendArtifactId: " + deployment.getFrontendArtifactId()));
                    frontendDeployService.deployFrontend(
                            frontendArtifact.getBucket(), frontendArtifact.getObjectKey(), appName, log);
                } catch (Exception fe) {
                    log.line("프론트 배포 실패" + (hasBackend ? "(백엔드는 계속 진행): [" : ": [")
                            + fe.getClass().getSimpleName() + "] " + fe.getMessage());
                    if (!hasBackend) {
                        throw fe;
                    }
                }
            }

            if (hasBackend) {
                log.line("앱 기동 대기 중");
            }
            boolean ready = !hasBackend
                    || kubernetesDeployService.waitForRollout(namespace, appName, DEFAULT_REPLICAS, 180, log);

            if (ready) {
                log.line(hasBackend ? "모든 replica Ready 확인됨" : "프론트 배포 완료");
                if (hasBackend) {
                    String endpointUrl = externalBaseUrl + "/" + namespace + "/" + appName;
                    deployment.setEndpointUrl(endpointUrl);
                    deploymentRepository.save(deployment);
                    currentStatus.value = DeploymentStatus.RUNNING;
                    log.line("상태 변경: RUNNING (endpoint=" + endpointUrl + ")");
                } else {
                    currentStatus.value = DeploymentStatus.RUNNING;
                    log.line("상태 변경: RUNNING (프론트만 배포 - S3 정적 호스팅)");
                }
            } else {
                log.line("제한시간 내에 Ready 상태가 되지 않음 - 실패한 컨테이너의 최근 로그:");
                String diagnostics = kubernetesDeployService.getPodFailureLogs(namespace, appName);
                for (String diagLine : diagnostics.split("\n")) {
                    log.line("  " + diagLine);
                }
                currentStatus.value = DeploymentStatus.FAILED;
                log.line("상태 변경: FAILED");
            }
        } catch (Exception e) {
            // 어느 단계(빌드/전송/배포/검증)에서 왜(예외 종류+메시지) 실패했는지 한 줄로 알 수 있게 남긴다.
            String reason = e.getMessage() != null ? e.getMessage() : "(메시지 없음)";
            currentStatus.value = DeploymentStatus.FAILED;
            log.line("배포 실패: [" + e.getClass().getSimpleName() + "] " + reason);
        } finally {
            // 성공/실패와 무관하게 항상 정리한다 - 안 하면 컨트롤 플레인의 작은 루트 디스크(7.6GB)에
            // 배포할 때마다 다운로드한 jar/zip이 쌓여 결국 디스크가 꽉 찬다(실제로 겪은 장애 -
            // "No space left on device"로 아티팩트 다운로드 자체가 실패하기 시작했었다).
            deleteWorkDir(workDir, log);
        }
    }

    private void deleteWorkDir(String workDir, DockerBuildService.LogSink log) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(workDir);
            if (!java.nio.file.Files.exists(dir)) {
                return;
            }
            try (var walk = java.nio.file.Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (java.io.IOException ignored) {
                        // 정리 실패는 배포 결과에 영향을 주면 안 되므로 무시한다.
                    }
                });
            }
        } catch (Exception e) {
            log.line("작업 디렉터리 정리 실패(무시됨): " + e.getMessage());
        }
    }

    /**
     * artifact의 bucket/object_key로 S3 다운로드용 presigned URL을 생성한다.
     * S3는 기본 비공개라 bucket+key만으로는 403이 나므로, 컨트롤 플레인 IAM 역할(s3:GetObject)로
     * 서명한 임시 URL을 만들어 준다. 이 URL을 E206(도커 빌드 워커)이 curl로 받아 이미지를 빌드한다.
     */
    private String resolveArtifactDownloadUrl(Artifact artifact) {
        return s3PresignService.presignGet(artifact.getBucket(), artifact.getObjectKey());
    }

    private static class StatusHolder {
        DeploymentStatus value;

        StatusHolder(DeploymentStatus initial) {
            this.value = initial;
        }
    }
}
