package com.ssafy.deployengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.ssafy.deployengine.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {
}
