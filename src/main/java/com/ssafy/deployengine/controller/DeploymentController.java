package com.ssafy.deployengine.controller;

import java.util.List;
import io.kubernetes.client.openapi.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ssafy.deployengine.dto.DeploymentLogResponse;
import com.ssafy.deployengine.dto.DeploymentMetricsRangeResponse;
import com.ssafy.deployengine.dto.DeploymentMetricsResponse;
import com.ssafy.deployengine.dto.DeploymentRequest;
import com.ssafy.deployengine.dto.DeploymentResponse;
import com.ssafy.deployengine.dto.ScaleRequest;
import com.ssafy.deployengine.dto.ScaleResponse;
import com.ssafy.deployengine.entity.Deployment;
import com.ssafy.deployengine.entity.DeploymentStatus;
import com.ssafy.deployengine.entity.Member;
import com.ssafy.deployengine.repository.DeploymentLogRepository;
import com.ssafy.deployengine.repository.DeploymentRepository;
import com.ssafy.deployengine.repository.MemberRepository;
import com.ssafy.deployengine.service.DeploymentLogService;
import com.ssafy.deployengine.service.KubernetesDeployService;
import com.ssafy.deployengine.service.MetricsService;
import com.ssafy.deployengine.service.RuntimeLogService;

@RestController
public class DeploymentController {

    // 한 배포의 replica는 이 이상으로 올릴 이유가 없고(팀 ResourceQuota의 pods=10 상한과도 맞음),
    // 실수로 큰 값이 들어와 노드를 압박하는 걸 막는다.
    private static final int MAX_REPLICAS = 10;

    private final DeploymentRepository deploymentRepository;
    private final DeploymentLogRepository deploymentLogRepository;
    private final MemberRepository memberRepository;
    private final DeploymentLogService deploymentLogService;
    private final MetricsService metricsService;
    private final KubernetesDeployService kubernetesDeployService;
    private final RuntimeLogService runtimeLogService;

    public DeploymentController(DeploymentRepository deploymentRepository,
                                 DeploymentLogRepository deploymentLogRepository,
                                 MemberRepository memberRepository,
                                 DeploymentLogService deploymentLogService,
                                 MetricsService metricsService,
                                 KubernetesDeployService kubernetesDeployService,
                                 RuntimeLogService runtimeLogService) {
        this.deploymentRepository = deploymentRepository;
        this.deploymentLogRepository = deploymentLogRepository;
        this.memberRepository = memberRepository;
        this.deploymentLogService = deploymentLogService;
        this.metricsService = metricsService;
        this.kubernetesDeployService = kubernetesDeployService;
        this.runtimeLogService = runtimeLogService;
    }

    // deployments 행 자체는 메인 서버가 이미 만들어뒀고, 우리는 id만 받아서 접수 로그(PENDING)를
    // 남긴다. 스레드를 오래 물고 있지 않기 위해 여기서는 접수만 하고 바로 202로 응답,
    // 실제 처리(빌드/배포)는 스케줄러가 비동기로 수행한다.
    @PostMapping("/api/deployments")
    public ResponseEntity<DeploymentResponse> create(@RequestBody DeploymentRequest request) {
        Long id = request.deploymentId();
        if (!deploymentRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        deploymentLogService.append(id, "배포 요청 접수", DeploymentStatus.PENDING);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new DeploymentResponse(id, "ACCEPTED"));
    }

    // 재배포 트리거(#6). 새 배포와 메커니즘이 완전히 동일하다 - PENDING 로그를 남기면 스케줄러가
    // 최신 로그 상태가 PENDING인 배포를 집어 전체 파이프라인(빌드->배포)을 다시 돈다.
    // create가 요청 바디로 id를 받는 것과 달리, 이미 존재하는 배포를 경로로 지목해 다시 돌리는 용도.
    @PostMapping("/api/deployments/{id}/redeploy")
    public ResponseEntity<DeploymentResponse> redeploy(@PathVariable Long id) {
        if (!deploymentRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        deploymentLogService.append(id, "재배포 요청 접수", DeploymentStatus.PENDING);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new DeploymentResponse(id, "ACCEPTED"));
    }

    @GetMapping("/api/deployments/{id}")
    public ResponseEntity<DeploymentResponse> get(@PathVariable Long id) {
        return deploymentLogRepository.findTopByDeploymentIdOrderByIdDesc(id)
                .map(latest -> new DeploymentResponse(id, latest.getStatus().name()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/deployments/{id}/logs")
    public ResponseEntity<List<DeploymentLogResponse>> logs(@PathVariable Long id) {
        List<DeploymentLogResponse> logs = deploymentLogRepository.findByDeploymentIdOrderByIdAsc(id)
                .stream()
                .map(DeploymentLogResponse::from)
                .toList();
        return ResponseEntity.ok(logs);
    }

    // 앱 런타임 로그(stdout/stderr) 최근 N줄 스냅샷. 기존 /logs(배포 상태 로그)와 구분해 /runtime-logs.
    // 화면 처음 열 때 한 번 불러 초기 내용을 채우는 용도(이후 실시간은 /stream).
    @GetMapping(value = "/api/deployments/{id}/runtime-logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> runtimeLogs(@PathVariable Long id,
                                              @RequestParam(defaultValue = "200") int tail) {
        Target target = resolve(id);
        if (target == null) {
            return ResponseEntity.notFound().build();
        }
        int lines = (int) clamp(tail, 1, 5000);
        return ResponseEntity.ok(runtimeLogService.getRecentLogs(target.namespace(), target.appName(), lines));
    }

    // Prometheus 폴링 방식 - 호출 시점의 CPU/메모리/네트워크/디스크/요청수 스냅샷.
    // 과거 추이(그래프)는 아래 /metrics/range 에서.
    @GetMapping("/api/deployments/{id}/metrics")
    public ResponseEntity<DeploymentMetricsResponse> metrics(@PathVariable Long id) {
        Target target = resolve(id);
        if (target == null) {
            return ResponseEntity.notFound().build();
        }
        DeploymentMetricsResponse metrics = metricsService.getMetrics(
                target.namespace(), target.appName(), target.port());
        return ResponseEntity.ok(metrics);
    }

    // 그래프용 시계열 - 최근 minutes분 동안의 추이를 stepSeconds 간격 샘플로 반환.
    // 지표별로 [{epochSeconds, value}, ...] 배열이 들어온다. 프론트가 이 배열을 그대로 라인차트에 그리면 됨.
    // 기본 30분/15초 간격(약 120포인트). Prometheus 보관 주기(retention)와 부하를 고려해 상한을 둔다.
    @GetMapping("/api/deployments/{id}/metrics/range")
    public ResponseEntity<DeploymentMetricsRangeResponse> metricsRange(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") int minutes,
            @RequestParam(defaultValue = "15") int stepSeconds) {
        Target target = resolve(id);
        if (target == null) {
            return ResponseEntity.notFound().build();
        }
        // Prometheus는 (end-start)/step 이 11000을 넘으면 쿼리를 거부한다. 과도한 요청을 막기 위해
        // 범위는 최대 24시간, step은 최소 5초로 클램프. 클램프해도 포인트 수가 11000 이하가 되도록 함.
        long rangeSeconds = clamp(minutes, 1, 24 * 60) * 60L;
        long step = clamp(stepSeconds, 5, 3600);
        if (rangeSeconds / step > 11000) {
            step = rangeSeconds / 11000 + 1;
        }
        DeploymentMetricsRangeResponse metrics = metricsService.getMetricsRange(
                target.namespace(), target.appName(), target.port(), rangeSeconds, step);
        return ResponseEntity.ok(metrics);
    }

    // replica 수 조절(#5). {"replicas": N} 을 받아 해당 배포의 Deployment를 스케일한다.
    // 상태 머신(deployment_logs 기반)은 배포 파이프라인 소유라, 스케일은 로그/상태를 건드리지 않는다.
    @PatchMapping("/api/deployments/{id}/scale")
    public ResponseEntity<?> scale(@PathVariable Long id, @RequestBody ScaleRequest request) {
        Target target = resolve(id);
        if (target == null) {
            return ResponseEntity.notFound().build();
        }
        Integer replicas = request.replicas();
        if (replicas == null || replicas < 0 || replicas > MAX_REPLICAS) {
            return ResponseEntity.badRequest()
                    .body("replicas는 0 이상 " + MAX_REPLICAS + " 이하여야 합니다: " + replicas);
        }
        try {
            kubernetesDeployService.scaleDeployment(target.namespace(), target.appName(), replicas);
            return ResponseEntity.ok(new ScaleResponse(id, replicas));
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                // DB엔 배포 행이 있지만 아직 k8s에 Deployment가 없음(미배포/실패/이미 삭제됨).
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body("아직 배포되지 않았거나 삭제된 앱이라 스케일할 수 없습니다.");
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("스케일 실패: " + e.getMessage());
        }
    }

    // 배포 리소스 삭제(#5). Deployment/Service/Ingress/Middleware를 정리한다(멱등).
    // 네임스페이스/Quota/NetworkPolicy는 팀 공용이라 남긴다. deployments 행은 백엔드 소유라 지우지 않고,
    // 우리가 채웠던 endpoint_url만 비워 "더 이상 접근 불가"를 반영한다.
    @DeleteMapping("/api/deployments/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        Target target = resolve(id);
        if (target == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            kubernetesDeployService.deleteAppResources(target.namespace(), target.appName());
            Deployment deployment = target.deployment();
            deployment.setEndpointUrl(null);
            deploymentRepository.save(deployment);
            return ResponseEntity.ok(new DeploymentResponse(id, "DELETED"));
        } catch (ApiException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("삭제 실패: " + e.getMessage());
        }
    }

    /**
     * deploymentId로부터 k8s 조작에 필요한 (namespace=팀, appName=slug, port)을 확정한다.
     * id->member->team_name 매핑을 거치므로, 여기가 "이 배포를 다룰 자격" 검사가 걸리는 지점이다.
     * 배포 행이나 소유 멤버가 없으면 null(→ 호출부에서 404).
     */
    private Target resolve(Long id) {
        Deployment deployment = deploymentRepository.findById(id).orElse(null);
        if (deployment == null) {
            return null;
        }
        Member member = memberRepository.findById(deployment.getMemberId()).orElse(null);
        if (member == null) {
            return null;
        }
        return new Target(com.ssafy.deployengine.support.Namespaces.toNamespace(member.getTeamName()),
                deployment.getSlug(), deployment.getInternalPort(), deployment);
    }

    private record Target(String namespace, String appName, int port, Deployment deployment) {
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
