package com.ssafy.deployengine.entity;

import java.util.List;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 백엔드팀이 관리하는 실제 deployments 테이블. 행 자체는 백엔드가 만들어서 넣어두고,
 * 우리는 deploymentId만 받아서 이 테이블을 조회해 필요한 정보(슬러그/포트/DB 정보/
 * 아티팩트 id 등)를 알아낸다. "현재 상태"는 이 테이블의 컬럼이 아니라
 * deployment_logs에서 가장 최근에 쌓인 로그의 status로 판단한다.
 */
@Entity
@Table(name = "deployments")
public class Deployment {

    @Id
    private Long id;

    @Column(name = "project_name")
    private String projectName;

    @Column(name = "slug")
    private String slug;

    @Column(name = "internal_port")
    private Integer internalPort;

    // Java는 어떤 JRE로 실행할지, Python/Node.js는 어떤 베이스 이미지 버전을 쓸지에 쓰인다.
    @Column(name = "runtime_version")
    private String runtimeVersion;

    // 언어+프레임워크("SPRING_BOOT"/"DJANGO"/"FASTAPI"/"EXPRESS"). 이 값에 맞춰
    // DockerBuildService가 Dockerfile을 자동 생성한다(사용자가 Dockerfile을 올리지 않음).
    @Column(name = "tech_stack")
    private String techStack;

    @Column(name = "database_name")
    private String databaseName;

    // 백엔드가 AES-GCM으로 암호화(v1: 접두사)해 저장하므로 SecretDecryptor로 복호화해서 써야 한다.
    @Column(name = "encrypted_database_password")
    private String databasePassword;

    // 관계형 DB 엔진("MYSQL"/"POSTGRES"). databaseName은 있는데 이 값이 없는 과거 데이터는 MYSQL로 간주한다.
    @Column(name = "database_engine")
    private String databaseEngine;

    // DB 초기화(테이블 생성) SQL 아티팩트 id. 없으면 null(마이그레이션 불필요/JPA 자동생성 등).
    @Column(name = "schema_artifact_id")
    private Long schemaArtifactId;

    // MySQL/PostgreSQL 외 추가로 필요한 백킹서비스, 콤마 구분 문자열(예: "REDIS,MONGODB"). 없으면 null.
    @Column(name = "needed_services")
    private String neededServices;

    @Column(name = "endpoint_url")
    private String endpointUrl;

    @Column(name = "backend_artifact_id")
    private Long backendArtifactId;

    @Column(name = "frontend_artifact_id")
    private Long frontendArtifactId;

    // 프론트 프레임워크("REACT"/"VUE"/"ANGULAR"/"SVELTE"/"PLAIN"). PLAIN이거나 값이 없으면
    // "이미 빌드된 정적 zip을 그대로 올린다"(과거 데이터 포함)로 간주해 Docker 빌드를 안 탄다.
    @Column(name = "frontend_stack")
    private String frontendStack;

    // 프론트 빌드에 쓸 Node 버전. backend_artifact의 runtime_version과 별개 컬럼 - 백엔드/프론트가
    // 같이 있는 분리형 배포에서 언어 자체가 다를 수 있어(예: 백엔드 Java, 프론트 Node) 공유할 수 없다.
    @Column(name = "frontend_runtime_version")
    private String frontendRuntimeVersion;

    @Column(name = "member_id")
    private Long memberId;

    protected Deployment() {
    }

    public Long getId() {
        return id;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getSlug() {
        return slug;
    }

    public Integer getInternalPort() {
        return internalPort != null ? internalPort : 8080;
    }

    public String getRuntimeVersion() {
        return (runtimeVersion == null || runtimeVersion.isBlank()) ? "17" : runtimeVersion.trim();
    }

    /** 과거 데이터(techStack 컬럼이 없던 시절, .jar만 지원)는 SPRING_BOOT로 간주한다. */
    public String getEffectiveTechStack() {
        return (techStack == null || techStack.isBlank()) ? "SPRING_BOOT" : techStack;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public boolean isNeedDatabase() {
        return databaseName != null && !databaseName.isBlank();
    }

    public String getDatabasePassword() {
        return databasePassword;
    }

    /** databaseName이 있는데 엔진이 비어 있는 과거 데이터는 MYSQL로 간주한다. */
    public String getEffectiveDatabaseEngine() {
        if (!isNeedDatabase()) {
            return null;
        }
        return (databaseEngine == null || databaseEngine.isBlank()) ? "MYSQL" : databaseEngine;
    }

    public Long getSchemaArtifactId() {
        return schemaArtifactId;
    }

    /** 콤마 구분 문자열("REDIS" 등)을 목록으로. 없으면 빈 목록. */
    public List<String> getNeededServices() {
        if (neededServices == null || neededServices.isBlank()) {
            return List.of();
        }
        return List.of(neededServices.split(","));
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
    }

    public Long getBackendArtifactId() {
        return backendArtifactId;
    }

    public Long getFrontendArtifactId() {
        return frontendArtifactId;
    }

    /** PLAIN이거나 값이 없으면(과거 데이터 포함) "이미 빌드된 정적 zip"으로 간주한다. */
    public String getEffectiveFrontendStack() {
        return (frontendStack == null || frontendStack.isBlank()) ? "PLAIN" : frontendStack;
    }

    public boolean isFrontendPrebuilt() {
        return "PLAIN".equals(getEffectiveFrontendStack());
    }

    public String getFrontendRuntimeVersion() {
        return (frontendRuntimeVersion == null || frontendRuntimeVersion.isBlank())
                ? "20" : frontendRuntimeVersion.trim();
    }

    public Long getMemberId() {
        return memberId;
    }
}
