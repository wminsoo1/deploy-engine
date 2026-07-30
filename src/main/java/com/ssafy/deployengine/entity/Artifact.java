package com.ssafy.deployengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 백엔드팀이 관리하는 업로드 파일(S3) 메타데이터. 읽기 전용. */
@Entity
@Table(name = "artifacts")
public class Artifact {

    @Id
    private Long id;

    @Column(name = "bucket")
    private String bucket;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "original_file_name")
    private String originalFileName;

    protected Artifact() {
    }

    public Long getId() {
        return id;
    }

    public String getBucket() {
        return bucket;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }
}
