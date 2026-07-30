package com.ssafy.deployengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.ssafy.deployengine.entity.Artifact;

public interface ArtifactRepository extends JpaRepository<Artifact, Long> {
}
