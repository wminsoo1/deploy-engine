package com.ssafy.deployengine.dto;

/** 스케일 요청 - 원하는 replica 수. (예: {"replicas": 3}) */
public record ScaleRequest(
        Integer replicas
) {
}
