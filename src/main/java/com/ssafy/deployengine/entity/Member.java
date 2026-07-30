package com.ssafy.deployengine.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 백엔드팀이 관리하는 회원 테이블. 읽기 전용 - team_name으로 네임스페이스를 정한다. */
@Entity
@Table(name = "members")
public class Member {

    @Id
    private Long id;

    @Column(name = "team_name")
    private String teamName;

    protected Member() {
    }

    public Long getId() {
        return id;
    }

    public String getTeamName() {
        return teamName;
    }
}
