package com.ssafy.deployengine.service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.apis.NetworkingV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerPort;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentSpec;
import io.kubernetes.client.openapi.models.V1DeploymentStrategy;
import io.kubernetes.client.openapi.models.V1EmptyDirVolumeSource;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1HTTPIngressPath;
import io.kubernetes.client.openapi.models.V1HTTPIngressRuleValue;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1IngressBackend;
import io.kubernetes.client.openapi.models.V1IngressRule;
import io.kubernetes.client.openapi.models.V1IngressServiceBackend;
import io.kubernetes.client.openapi.models.V1IngressSpec;
import io.kubernetes.client.openapi.models.V1LabelSelector;
import io.kubernetes.client.openapi.models.V1LimitRange;
import io.kubernetes.client.openapi.models.V1LimitRangeItem;
import io.kubernetes.client.openapi.models.V1LimitRangeSpec;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NetworkPolicy;
import io.kubernetes.client.openapi.models.V1NetworkPolicyIngressRule;
import io.kubernetes.client.openapi.models.V1NetworkPolicyPeer;
import io.kubernetes.client.openapi.models.V1NetworkPolicySpec;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1Probe;
import io.kubernetes.client.openapi.models.V1ResourceQuota;
import io.kubernetes.client.openapi.models.V1ResourceQuotaSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1ServiceBackendPort;
import io.kubernetes.client.openapi.models.V1ServicePort;
import io.kubernetes.client.openapi.models.V1ServiceSpec;
import io.kubernetes.client.openapi.models.V1TCPSocketAction;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import io.kubernetes.client.util.Config;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 실제 k8s 리소스 생성을 담당. kubectl을 셸아웃하지 않고 공식 client-java로
 * API를 직접 호출한다 (k3s도 표준 k8s API라 별도 처리 불필요).
 */
@Service
public class KubernetesDeployService {

    @Value("${deploy.kubeconfig-path}")
    private String kubeconfigPath;

    // 서브도메인 Host 기반 라우팅(applyHostRoute)에 쓰는 공개 도메인 - {slug}.{publicDomain}
    @Value("${deploy.public-domain:ssafyhub.site}")
    private String publicDomain;

    @Value("${deploy.frontend.bucket:ssafy-deploy-frontend}")
    private String frontendBucket;

    @Value("${deploy.aws-region:ap-northeast-2}")
    private String awsRegion;

    private CoreV1Api coreApi;
    private AppsV1Api appsApi;
    private NetworkingV1Api networkingApi;
    private CustomObjectsApi customObjectsApi;

    @PostConstruct
    public void init() throws Exception {
        ApiClient client = Config.fromConfig(kubeconfigPath);
        Configuration.setDefaultApiClient(client);
        this.coreApi = new CoreV1Api();
        this.appsApi = new AppsV1Api();
        this.networkingApi = new NetworkingV1Api();
        this.customObjectsApi = new CustomObjectsApi();
    }

    public void ensureNamespace(String namespace) throws ApiException {
        try {
            coreApi.readNamespace(namespace).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                V1Namespace ns = new V1Namespace().metadata(new V1ObjectMeta().name(namespace));
                coreApi.createNamespace(ns).execute();
            } else {
                throw e;
            }
        }
        ensureTeamIsolation(namespace);
    }

    /**
     * 팀(네임스페이스)별 격리: 리소스는 LimitRange(컨테이너 기본값)+ResourceQuota(팀 전체 상한)로,
     * 네트워크는 NetworkPolicy로 "같은 팀 + Traefik(kube-system)"만 허용하고 다른 팀은 차단한다.
     * 워커가 SSAFY 제공 E206(15GB, 워커 1대)이라 팀당 request 3Gi/limit 4Gi로 잡았다 -
     * 팀 하나가 노드 전체를 통째로 못 먹게 하면서, 팀당 앱 여러 개 + DB 정도는 여유 있게 돌게.
     */
    private void ensureTeamIsolation(String namespace) throws ApiException {
        V1LimitRange limitRange = new V1LimitRange()
                .metadata(new V1ObjectMeta().name("default-limits").namespace(namespace))
                .spec(new V1LimitRangeSpec().limits(List.of(new V1LimitRangeItem()
                        .type("Container")
                        ._default(Map.of("cpu", new Quantity("300m"), "memory", new Quantity("512Mi")))
                        .defaultRequest(Map.of("cpu", new Quantity("100m"), "memory", new Quantity("256Mi")))
                        .max(Map.of("cpu", new Quantity("1"), "memory", new Quantity("1Gi")))
                        .min(Map.of("cpu", new Quantity("50m"), "memory", new Quantity("32Mi"))))));
        try {
            coreApi.readNamespacedLimitRange("default-limits", namespace).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                coreApi.createNamespacedLimitRange(namespace, limitRange).execute();
            } else {
                throw e;
            }
        }

        V1ResourceQuota quota = new V1ResourceQuota()
                .metadata(new V1ObjectMeta().name("team-quota").namespace(namespace))
                .spec(new V1ResourceQuotaSpec().hard(Map.of(
                        "requests.cpu", new Quantity("2"),
                        "requests.memory", new Quantity("3Gi"),
                        "limits.cpu", new Quantity("4"),
                        "limits.memory", new Quantity("4Gi"),
                        "pods", new Quantity("10"))));
        try {
            coreApi.readNamespacedResourceQuota("team-quota", namespace).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                coreApi.createNamespacedResourceQuota(namespace, quota).execute();
            } else {
                throw e;
            }
        }

        V1NetworkPolicy networkPolicy = new V1NetworkPolicy()
                .metadata(new V1ObjectMeta().name("team-isolation").namespace(namespace))
                .spec(new V1NetworkPolicySpec()
                        .podSelector(new V1LabelSelector())
                        .policyTypes(List.of("Ingress"))
                        .ingress(List.of(new V1NetworkPolicyIngressRule().from(List.of(
                                new V1NetworkPolicyPeer().podSelector(new V1LabelSelector()),
                                new V1NetworkPolicyPeer().namespaceSelector(new V1LabelSelector()
                                        .matchLabels(Map.of("kubernetes.io/metadata.name", "kube-system"))))))));
        try {
            networkingApi.readNamespacedNetworkPolicy("team-isolation", namespace).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                networkingApi.createNamespacedNetworkPolicy(namespace, networkPolicy).execute();
            } else {
                throw e;
            }
        }
    }

    /**
     * needDatabase 배포를 위한 MySQL을 이 배포(appName) 전용으로 보장한다(없으면 생성). 도커
     * 컴포즈로 app+db를 함께 띄우는 것과 비슷 - "{appName}-mysql" Deployment + Service를 만들어
     * 앱(및 socat 사이드카)이 그 이름:3306으로 붙게 한다. 예전엔 네임스페이스(팀)당 "mysql" 하나를
     * 공유했는데, 그러면 같은 팀의 다른 배포/다른 사람이 다른 비밀번호로 배포해도 최초 생성 시점
     * 비밀번호로 고정돼버려 "내가 입력한 비밀번호가 반영 안 됨" 문제가 생겼다. 이제는 배포마다
     * 독립된 MySQL이라 그럴 일이 없다 - 대신 서로 DB를 공유하지도 않는다(요구사항).
     * - 이미 있으면 그대로 둔다(멱등 - 같은 프로젝트를 재배포할 때 기존 데이터/연결 보존)
     * - mysql은 공개 이미지라 Docker Hub에서 pull(imagePullPolicy IfNotPresent) - 앱 이미지의 Never와 다름
     * - 저장은 emptyDir(파드 재시작 시 데이터 소실) - 우선 동작 우선, 영구화는 추후 PVC로
     * - root 및 databaseName과 같은 이름의 계정을 같은 비밀번호로 만든다
     *   (앱이 root 규약을 안 따르고 자체 계정을 쓰는 경우까지 커버)
     */
    public void ensureMysql(String namespace, String appName, String databaseName, String password)
            throws ApiException {
        String app = appName + "-mysql";
        try {
            appsApi.readNamespacedDeployment(app, namespace).execute();
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
            appsApi.createNamespacedDeployment(namespace,
                    buildMysqlDeployment(namespace, app, databaseName, password)).execute();
        }

        V1Service service = new V1Service()
                .metadata(new V1ObjectMeta().name(app).namespace(namespace))
                .spec(new V1ServiceSpec()
                        .selector(Map.of("app", app))
                        .ports(List.of(new V1ServicePort().port(3306)
                                .targetPort(new io.kubernetes.client.custom.IntOrString(3306)))));
        try {
            coreApi.readNamespacedService(app, namespace).execute();
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
            coreApi.createNamespacedService(namespace, service).execute();
        }
    }

    private V1Deployment buildMysqlDeployment(String namespace, String app, String databaseName, String password) {
        List<V1EnvVar> env = new java.util.ArrayList<>();
        env.add(new V1EnvVar().name("MYSQL_ROOT_PASSWORD").value(password));
        if (databaseName != null && !databaseName.isBlank()) {
            env.add(new V1EnvVar().name("MYSQL_DATABASE").value(databaseName));
            // 앱이 root가 아니라 자체 계정(흔히 db명과 같은 이름)을 쓰는 경우까지 커버
            env.add(new V1EnvVar().name("MYSQL_USER").value(databaseName));
            env.add(new V1EnvVar().name("MYSQL_PASSWORD").value(password));
        }

        // mysqld는 초기화가 끝나야 3306을 연다. TCP readiness로 "접속 가능"해질 때까지 기다리는 신호로 쓴다.
        V1Probe readiness = new V1Probe()
                .tcpSocket(new V1TCPSocketAction().port(new io.kubernetes.client.custom.IntOrString(3306)))
                .initialDelaySeconds(10).periodSeconds(5).failureThreshold(30);

        V1Container container = new V1Container()
                .name(app)
                .image("mysql:8.0")
                .imagePullPolicy("IfNotPresent") // 공개 이미지 - Docker Hub에서 pull (앱 이미지의 Never와 다름)
                .addPortsItem(new V1ContainerPort().containerPort(3306))
                .env(env)
                .readinessProbe(readiness)
                .addVolumeMountsItem(new V1VolumeMount().name("data").mountPath("/var/lib/mysql"))
                .resources(new V1ResourceRequirements()
                        .requests(Map.of("memory", new Quantity("512Mi")))
                        .limits(Map.of("memory", new Quantity("1Gi"))));

        V1PodTemplateSpec template = new V1PodTemplateSpec()
                .metadata(new V1ObjectMeta().labels(Map.of("app", app)))
                .spec(new V1PodSpec()
                        .containers(List.of(container))
                        .volumes(List.of(new V1Volume().name("data").emptyDir(new V1EmptyDirVolumeSource()))));

        return new V1Deployment()
                .metadata(new V1ObjectMeta().name(app).namespace(namespace))
                .spec(new V1DeploymentSpec()
                        .replicas(1)
                        .selector(new V1LabelSelector().matchLabels(Map.of("app", app)))
                        .template(template)
                        .strategy(new V1DeploymentStrategy().type("Recreate")));
    }

    /**
     * Redis가 필요한 배포를 위해 이 배포(appName) 전용 Redis를 보장한다(없으면 생성) - ensureMysql과
     * 같은 패턴/같은 이유(팀 공유 대신 배포별 독립). 인증 없이(간단한 개발/데모 용도)
     * "{appName}-redis" Service(6379)로 붙게 한다.
     * - 이미 있으면 그대로 둔다(멱등)
     * - 저장은 emptyDir(파드 재시작 시 데이터 소실) - 캐시/세션 용도라 mysql과 달리 영구성 요구가 낮음
     */
    public void ensureRedis(String namespace, String appName) throws ApiException {
        String app = appName + "-redis";
        try {
            appsApi.readNamespacedDeployment(app, namespace).execute();
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
            appsApi.createNamespacedDeployment(namespace, buildRedisDeployment(namespace, app)).execute();
        }

        V1Service service = new V1Service()
                .metadata(new V1ObjectMeta().name(app).namespace(namespace))
                .spec(new V1ServiceSpec()
                        .selector(Map.of("app", app))
                        .ports(List.of(new V1ServicePort().port(6379)
                                .targetPort(new io.kubernetes.client.custom.IntOrString(6379)))));
        try {
            coreApi.readNamespacedService(app, namespace).execute();
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
            coreApi.createNamespacedService(namespace, service).execute();
        }
    }

    private V1Deployment buildRedisDeployment(String namespace, String app) {
        V1Probe readiness = new V1Probe()
                .tcpSocket(new V1TCPSocketAction().port(new io.kubernetes.client.custom.IntOrString(6379)))
                .initialDelaySeconds(3).periodSeconds(3).failureThreshold(10);

        V1Container container = new V1Container()
                .name(app)
                .image("redis:7-alpine")
                .imagePullPolicy("IfNotPresent") // 공개 이미지 - Docker Hub에서 pull
                .addPortsItem(new V1ContainerPort().containerPort(6379))
                .readinessProbe(readiness)
                .addVolumeMountsItem(new V1VolumeMount().name("data").mountPath("/data"))
                .resources(new V1ResourceRequirements()
                        .requests(Map.of("memory", new Quantity("128Mi")))
                        .limits(Map.of("memory", new Quantity("256Mi"))));

        V1PodTemplateSpec template = new V1PodTemplateSpec()
                .metadata(new V1ObjectMeta().labels(Map.of("app", app)))
                .spec(new V1PodSpec()
                        .containers(List.of(container))
                        .volumes(List.of(new V1Volume().name("data").emptyDir(new V1EmptyDirVolumeSource()))));

        return new V1Deployment()
                .metadata(new V1ObjectMeta().name(app).namespace(namespace))
                .spec(new V1DeploymentSpec()
                        .replicas(1)
                        .selector(new V1LabelSelector().matchLabels(Map.of("app", app)))
                        .template(template)
                        .strategy(new V1DeploymentStrategy().type("Recreate")));
    }

    /**
     * MongoDB가 필요한 배포를 위해 이 배포(appName) 전용 Mongo를 보장한다 - ensureRedis와 같은 패턴.
     * 인증 없이(간단한 개발/데모 용도) "{appName}-mongo" Service(27017)로 붙게 한다.
     */
    public void ensureMongo(String namespace, String appName) throws ApiException {
        String app = appName + "-mongo";
        try {
            appsApi.readNamespacedDeployment(app, namespace).execute();
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
            appsApi.createNamespacedDeployment(namespace, buildMongoDeployment(namespace, app)).execute();
        }

        V1Service service = new V1Service()
                .metadata(new V1ObjectMeta().name(app).namespace(namespace))
                .spec(new V1ServiceSpec()
                        .selector(Map.of("app", app))
                        .ports(List.of(new V1ServicePort().port(27017)
                                .targetPort(new io.kubernetes.client.custom.IntOrString(27017)))));
        try {
            coreApi.readNamespacedService(app, namespace).execute();
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
            coreApi.createNamespacedService(namespace, service).execute();
        }
    }

    private V1Deployment buildMongoDeployment(String namespace, String app) {
        V1Probe readiness = new V1Probe()
                .tcpSocket(new V1TCPSocketAction().port(new io.kubernetes.client.custom.IntOrString(27017)))
                .initialDelaySeconds(5).periodSeconds(5).failureThreshold(20);

        V1Container container = new V1Container()
                .name(app)
                .image("mongo:7")
                .imagePullPolicy("IfNotPresent")
                .addPortsItem(new V1ContainerPort().containerPort(27017))
                .readinessProbe(readiness)
                .addVolumeMountsItem(new V1VolumeMount().name("data").mountPath("/data/db"))
                .resources(new V1ResourceRequirements()
                        .requests(Map.of("memory", new Quantity("256Mi")))
                        .limits(Map.of("memory", new Quantity("512Mi"))));

        V1PodTemplateSpec template = new V1PodTemplateSpec()
                .metadata(new V1ObjectMeta().labels(Map.of("app", app)))
                .spec(new V1PodSpec()
                        .containers(List.of(container))
                        .volumes(List.of(new V1Volume().name("data").emptyDir(new V1EmptyDirVolumeSource()))));

        return new V1Deployment()
                .metadata(new V1ObjectMeta().name(app).namespace(namespace))
                .spec(new V1DeploymentSpec()
                        .replicas(1)
                        .selector(new V1LabelSelector().matchLabels(Map.of("app", app)))
                        .template(template)
                        .strategy(new V1DeploymentStrategy().type("Recreate")));
    }

    public void applyDeployment(String namespace, String appName, String imageTag, int port,
                                 int replicas, String memoryLimit, Map<String, String> env,
                                 String databaseEngine) throws ApiException {
        List<V1EnvVar> envVars = env.entrySet().stream()
                .map(e -> new V1EnvVar().name(e.getKey()).value(e.getValue()))
                .toList();

        // readinessProbe가 없으면 k8s는 "컨테이너 프로세스가 시작됨"만 보고 바로 Ready 처리한다.
        // 그런데 Spring Boot는 DB 연결 등 초기화가 실패하면 포트를 열기 전에 애플리케이션
        // 컨텍스트째로 죽는다 - 즉 포트가 열렸는지가 "진짜 떴는지"의 신호다. 이게 없으면
        // waitForRollout이 "죽기 직전 잠깐 떠 있던 순간"을 Ready로 착각해 RUNNING으로 오판할 수 있다.
        V1Probe readinessProbe = new V1Probe()
                .tcpSocket(new V1TCPSocketAction().port(new io.kubernetes.client.custom.IntOrString(port)))
                .initialDelaySeconds(2)
                .periodSeconds(3)
                .failureThreshold(3);

        V1Container container = new V1Container()
                .name(appName)
                .image(imageTag)
                .imagePullPolicy("Never") // 레지스트리 없이 워커에 직접 import한 이미지를 쓰기 때문
                .addPortsItem(new V1ContainerPort().containerPort(port))
                .env(envVars)
                .readinessProbe(readinessProbe)
                // request를 limit과 동일하게 줘야 스케줄러가 "이 노드에 실제로 여유가 있는지"를
                // 보고 배치한다. request가 없으면 0으로 취급돼서 이미 꽉 찬 노드에도 올라가버린다.
                .resources(new V1ResourceRequirements()
                        .limits(Map.of("memory", new Quantity(memoryLimit)))
                        .requests(Map.of("memory", new Quantity(memoryLimit))));

        List<V1Container> containers = new java.util.ArrayList<>();
        containers.add(container);

        if (databaseEngine != null) {
            // DB_HOST 환경변수 컨벤션을 안 따르고 localhost로 하드코딩한 앱도 동작하도록,
            // 같은 Pod 안에서 3306을 리스닝해 이 배포 전용 mysql Service(3306)로 그대로 포워딩하는
            // 사이드카. 같은 Pod의 컨테이너들은 네트워크 네임스페이스를 공유하므로 앱 입장에선
            // 진짜 localhost:3306처럼 보인다.
            V1Container dbProxy = new V1Container()
                    .name("db-proxy")
                    .image("alpine/socat")
                    .args(List.of("TCP-LISTEN:3306,fork,reuseaddr", "TCP:" + appName + "-mysql:3306"));
            containers.add(dbProxy);
        }

        V1PodTemplateSpec podTemplate = new V1PodTemplateSpec()
                .metadata(new V1ObjectMeta().labels(Map.of("app", appName)))
                .spec(new V1PodSpec().containers(containers));

        V1Deployment deployment = new V1Deployment()
                .metadata(new V1ObjectMeta().name(appName).namespace(namespace))
                .spec(new V1DeploymentSpec()
                        .replicas(replicas)
                        .selector(new V1LabelSelector().matchLabels(Map.of("app", appName)))
                        .template(podTemplate)
                        // 기본 RollingUpdate는 새 Pod를 먼저 띄우고 나서 이전 Pod를 내리기 때문에
                        // 그 순간 두 세대 분의 리소스가 동시에 필요하다. 팀당 ResourceQuota를
                        // "앱 1개분"으로 빠듯하게 잡아서, 그 여유가 없으면 재배포마다 quota 초과로
                        // 실패한다. Recreate는 이전 Pod를 먼저 내리고 나서 새 Pod를 띄우므로 순간
                        // 다운타임은 있지만 이 플랫폼처럼 빠듯한 팀별 할당량과 맞다.
                        .strategy(new V1DeploymentStrategy().type("Recreate")));

        try {
            appsApi.readNamespacedDeployment(appName, namespace).execute();
            appsApi.replaceNamespacedDeployment(appName, namespace, deployment).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                appsApi.createNamespacedDeployment(namespace, deployment).execute();
            } else {
                throw e;
            }
        }
    }

    /**
     * Deployment의 replica 수만 바꾼다. 노드 대수와 무관 - 스케줄러가 replica들을
     * 가용 노드에 알아서 분산 배치한다. quota(팀당 requests.memory 등) 초과로 새 replica가
     * 못 뜨는 경우, scale 호출 자체는 성공하고 파드가 Pending으로 남는다(quota 판단은 스케줄러 몫).
     */
    public void scaleDeployment(String namespace, String appName, int replicas) throws ApiException {
        V1Deployment d = appsApi.readNamespacedDeployment(appName, namespace).execute();
        if (d.getSpec() == null) {
            throw new ApiException(500, "Deployment에 spec이 없음: " + appName);
        }
        d.getSpec().replicas(replicas);
        // status는 replace 시 apiserver가 어차피 무시한다. client-java가 모르는 최신 status 필드로
        // 인한 전송 문제를 피하려고 명시적으로 비운다(spec만 반영하면 됨).
        d.setStatus(null);
        appsApi.replaceNamespacedDeployment(appName, namespace, d).execute();
    }

    /**
     * 한 배포에 속한 리소스 전부를 정리한다(apply의 역순): Deployment -> Service -> Ingress -> Middleware.
     * 이미 없는 건 404를 무시(멱등)한다. Deployment를 지우면 그에 속한 파드는 어느 노드에 떠 있든
     * 클러스터 전체에서 함께 사라지므로, 삭제도 노드 대수와 무관하다.
     * mysql/redis/mongo는 이제 이 배포 전용(다른 배포와 공유 안 함)이라 같이 지운다 - 안 그러면
     * 재배포 시 예전 비밀번호가 남아있는 채로 새 비밀번호와 안 맞는 문제가 생긴다.
     * 네임스페이스/ResourceQuota/NetworkPolicy는 팀 공용이라 여기서 지우지 않는다.
     */
    public void deleteAppResources(String namespace, String appName) throws ApiException {
        String middlewareName = appName + "-strip-prefix";
        String addPrefixName = appName + "-add-slug";
        String hostRouteName = appName + "-host";
        deleteIfExists(() -> appsApi.deleteNamespacedDeployment(appName, namespace).execute());
        deleteIfExists(() -> coreApi.deleteNamespacedService(appName, namespace).execute());
        deleteIfExists(() -> networkingApi.deleteNamespacedIngress(appName, namespace).execute());
        deleteIfExists(() -> customObjectsApi.deleteNamespacedCustomObject(
                "traefik.io", "v1alpha1", namespace, "middlewares", middlewareName).execute());
        deleteIfExists(() -> customObjectsApi.deleteNamespacedCustomObject(
                "traefik.io", "v1alpha1", namespace, "ingressroutes", hostRouteName).execute());
        deleteIfExists(() -> customObjectsApi.deleteNamespacedCustomObject(
                "traefik.io", "v1alpha1", namespace, "middlewares", addPrefixName).execute());
        for (String suffix : List.of("-mysql", "-redis", "-mongo")) {
            deleteIfExists(() -> appsApi.deleteNamespacedDeployment(appName + suffix, namespace).execute());
            deleteIfExists(() -> coreApi.deleteNamespacedService(appName + suffix, namespace).execute());
        }
        // frontend-s3(ExternalName Service)/frontend-s3-host-rewrite(Middleware)는 정말로 여러
        // 배포가 함께 쓰는 네임스페이스 공용 리소스라 여기서 안 지운다.
    }

    @FunctionalInterface
    private interface ApiCall {
        void run() throws ApiException;
    }

    private void deleteIfExists(ApiCall call) throws ApiException {
        try {
            call.run();
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e; // 이미 없는 건 정상(멱등). 그 외 에러만 전파한다.
            }
        }
    }

    public void applyService(String namespace, String appName, int port) throws ApiException {
        V1Service service = new V1Service()
                .metadata(new V1ObjectMeta().name(appName).namespace(namespace))
                .spec(new V1ServiceSpec()
                        .selector(Map.of("app", appName))
                        .ports(List.of(new V1ServicePort().port(port).targetPort(new io.kubernetes.client.custom.IntOrString(port)))));

        try {
            coreApi.readNamespacedService(appName, namespace).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                coreApi.createNamespacedService(namespace, service).execute();
            } else {
                throw e;
            }
        }
    }

    /**
     * 메인 서버(ssafyhub.site)가 "/{team}/{app}" 경로로 프록시해 줄 진입점.
     * namespace가 곧 팀 이름이라 경로의 첫 세그먼트와 1:1로 맞아떨어진다.
     * 앱 자신은 이 접두사를 모르고 자기 경로(예: /hello)로만 응답하기 때문에,
     * Traefik Middleware로 접두사를 벗겨내고 나서 Service로 넘긴다.
     */
    public void applyIngress(String namespace, String appName, int port) throws ApiException {
        String path = "/" + namespace + "/" + appName;
        String middlewareName = appName + "-strip-prefix";
        applyStripPrefixMiddleware(namespace, middlewareName, path);

        V1HTTPIngressPath httpPath = new V1HTTPIngressPath()
                .path(path)
                .pathType("Prefix")
                .backend(new V1IngressBackend().service(
                        new V1IngressServiceBackend().name(appName).port(new V1ServiceBackendPort().number(port))));

        V1Ingress ingress = new V1Ingress()
                .metadata(new V1ObjectMeta().name(appName).namespace(namespace)
                        .annotations(Map.of("traefik.ingress.kubernetes.io/router.middlewares",
                                namespace + "-" + middlewareName + "@kubernetescrd")))
                .spec(new V1IngressSpec()
                        .ingressClassName("traefik")
                        .rules(List.of(new V1IngressRule().http(new V1HTTPIngressRuleValue().paths(List.of(httpPath))))));

        try {
            networkingApi.readNamespacedIngress(appName, namespace).execute();
            networkingApi.replaceNamespacedIngress(appName, namespace, ingress).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                networkingApi.createNamespacedIngress(namespace, ingress).execute();
            } else {
                throw e;
            }
        }
    }

    /**
     * {slug}.{publicDomain}로 들어온 요청이 메인 서버(SubdomainProxyFilter)를 거치지 않고
     * Traefik에서 바로 이 배포의 Service로 가도록 Host 기반 IngressRoute를 만든다.
     * applyIngress(경로 기반, 메인 서버 프록시용)와 별개로 존재 - 메인 서버 경유 방식을 당장
     * 걷어내지 않고 두 경로를 병행하기 위함(DNS를 Traefik으로 돌리기 전까진 이 라우트는 안 쓰인다).
     *
     * - 통합형(hasFrontend=false): 전부 이 앱의 Service로 (경로 유지, 벗겨낼 접두사 없음)
     * - 분리형(hasFrontend=true): "/api"로 시작하면 이 앱의 Service로(경로 그대로 전달 -
     *   메인 서버 프록시와 달리 "/api"를 벗기지 않는다. 이 배포 데모들도 전부 "/api/..."
     *   경로로 직접 구현돼 있어, 벗기지 않는 쪽이 실제 앱 구현과 맞는다), 그 외 전부
     *   공용 S3 정적 호스팅으로 - 단, S3의 실제 객체 위치가 "{slug}/..."라서 addPrefix로
     *   "/{slug}"를 앞에 붙여준 뒤에 보내야 한다.
     */
    public void applyHostRoute(String namespace, String appName, int port, boolean hasFrontend) throws ApiException {
        String host = appName + "." + publicDomain;
        String routeName = appName + "-host";

        List<Map<String, Object>> routes = new java.util.ArrayList<>();
        Map<String, Object> appService = Map.of("name", appName, "port", port, "kind", "Service");

        if (!hasFrontend) {
            routes.add(Map.of(
                    "kind", "Rule",
                    "match", "Host(`" + host + "`)",
                    "services", List.of(appService)));
        } else {
            String addPrefixName = appName + "-add-slug";
            applyAddPrefixMiddleware(namespace, addPrefixName, "/" + appName);
            // S3 정적 웹사이트 호스팅은 Host 헤더로 버킷을 구분하는 가상 호스팅 방식이라,
            // 브라우저가 보낸 원래 Host({slug}.{publicDomain})를 그대로 넘기면 "그 이름의
            // 버킷"을 찾다가 NoSuchBucket이 난다. S3 엔드포인트 자신의 호스트명으로 덮어써야 한다.
            String hostRewriteName = "frontend-s3-host-rewrite";
            applyHostRewriteMiddleware(namespace, hostRewriteName,
                    frontendBucket + ".s3-website." + awsRegion + ".amazonaws.com");

            routes.add(Map.of(
                    "kind", "Rule",
                    "match", "Host(`" + host + "`) && PathPrefix(`/api`)",
                    "priority", 10,
                    "services", List.of(appService)));

            Map<String, Object> frontendService = Map.of("name", "frontend-s3", "port", 80, "kind", "Service");
            routes.add(Map.of(
                    "kind", "Rule",
                    "match", "Host(`" + host + "`)",
                    "priority", 1,
                    "middlewares", List.of(Map.of("name", hostRewriteName), Map.of("name", addPrefixName)),
                    "services", List.of(frontendService)));
        }

        applyIngressRoute(namespace, routeName, routes);
    }

    private void applyIngressRoute(String namespace, String name, List<Map<String, Object>> routes)
            throws ApiException {
        Map<String, Object> metadata = new java.util.HashMap<>(Map.of("name", name, "namespace", namespace));
        Map<String, Object> spec = Map.of("entryPoints", List.of("web", "websecure"), "routes", routes);
        try {
            Object existing = customObjectsApi.getNamespacedCustomObject(
                    "traefik.io", "v1alpha1", namespace, "ingressroutes", name).execute();
            String resourceVersion = extractResourceVersion(existing);
            if (resourceVersion != null) {
                metadata.put("resourceVersion", resourceVersion);
            }
            Map<String, Object> ingressRoute = Map.of(
                    "apiVersion", "traefik.io/v1alpha1", "kind", "IngressRoute", "metadata", metadata, "spec", spec);
            customObjectsApi.replaceNamespacedCustomObject(
                    "traefik.io", "v1alpha1", namespace, "ingressroutes", name, ingressRoute).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                Map<String, Object> ingressRoute = Map.of(
                        "apiVersion", "traefik.io/v1alpha1", "kind", "IngressRoute",
                        "metadata", metadata, "spec", spec);
                customObjectsApi.createNamespacedCustomObject(
                        "traefik.io", "v1alpha1", namespace, "ingressroutes", ingressRoute).execute();
            } else {
                throw e;
            }
        }
    }

    private void applyHostRewriteMiddleware(String namespace, String name, String rewriteHost) throws ApiException {
        Map<String, Object> metadata = new java.util.HashMap<>(Map.of("name", name, "namespace", namespace));
        Map<String, Object> spec = Map.of("headers", Map.of("customRequestHeaders", Map.of("Host", rewriteHost)));
        try {
            Object existing = customObjectsApi.getNamespacedCustomObject(
                    "traefik.io", "v1alpha1", namespace, "middlewares", name).execute();
            String resourceVersion = extractResourceVersion(existing);
            if (resourceVersion != null) {
                metadata.put("resourceVersion", resourceVersion);
            }
            Map<String, Object> middleware = Map.of(
                    "apiVersion", "traefik.io/v1alpha1", "kind", "Middleware", "metadata", metadata, "spec", spec);
            customObjectsApi.replaceNamespacedCustomObject(
                    "traefik.io", "v1alpha1", namespace, "middlewares", name, middleware).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                Map<String, Object> middleware = Map.of(
                        "apiVersion", "traefik.io/v1alpha1", "kind", "Middleware",
                        "metadata", metadata, "spec", spec);
                customObjectsApi.createNamespacedCustomObject(
                        "traefik.io", "v1alpha1", namespace, "middlewares", middleware).execute();
            } else {
                throw e;
            }
        }
    }

    private void applyAddPrefixMiddleware(String namespace, String name, String prefix) throws ApiException {
        Map<String, Object> metadata = new java.util.HashMap<>(Map.of("name", name, "namespace", namespace));
        Map<String, Object> spec = Map.of("addPrefix", Map.of("prefix", prefix));
        try {
            Object existing = customObjectsApi.getNamespacedCustomObject(
                    "traefik.io", "v1alpha1", namespace, "middlewares", name).execute();
            String resourceVersion = extractResourceVersion(existing);
            if (resourceVersion != null) {
                metadata.put("resourceVersion", resourceVersion);
            }
            Map<String, Object> middleware = Map.of(
                    "apiVersion", "traefik.io/v1alpha1", "kind", "Middleware", "metadata", metadata, "spec", spec);
            customObjectsApi.replaceNamespacedCustomObject(
                    "traefik.io", "v1alpha1", namespace, "middlewares", name, middleware).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                Map<String, Object> middleware = Map.of(
                        "apiVersion", "traefik.io/v1alpha1", "kind", "Middleware",
                        "metadata", metadata, "spec", spec);
                customObjectsApi.createNamespacedCustomObject(
                        "traefik.io", "v1alpha1", namespace, "middlewares", middleware).execute();
            } else {
                throw e;
            }
        }
    }

    /**
     * 팀 네임스페이스마다 하나만 있으면 되는 공용 리소스 - S3 정적 호스팅(공용 버킷)을 가리키는
     * ExternalName Service. 프론트 버킷은 모든 배포가 공유하고 slug만 객체 키 접두사로 다르므로,
     * 버킷 하나당 Service 하나면 충분하다(멱등 - 이미 있으면 그대로 둔다).
     */
    public void ensureFrontendExternalService(String namespace) throws ApiException {
        String name = "frontend-s3";
        try {
            coreApi.readNamespacedService(name, namespace).execute();
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
            V1Service service = new V1Service()
                    .metadata(new V1ObjectMeta().name(name).namespace(namespace))
                    .spec(new V1ServiceSpec()
                            .type("ExternalName")
                            .externalName(frontendBucket + ".s3-website." + awsRegion + ".amazonaws.com")
                            .ports(List.of(new V1ServicePort().port(80)
                                    .targetPort(new io.kubernetes.client.custom.IntOrString(80)))));
            coreApi.createNamespacedService(namespace, service).execute();
        }
    }

    private void applyStripPrefixMiddleware(String namespace, String name, String path) throws ApiException {
        // CustomObjectsApi는 타입 없는 Object/Map으로 주고받는데, Traefik의 Middleware CRD는
        // (일반 core 리소스와 달리) update 시 metadata.resourceVersion이 반드시 있어야 해서
        // 없으면 422로 거부한다. 그래서 여기만 기존 리소스를 먼저 읽어서 resourceVersion을 넣어준다.
        Map<String, Object> metadata = new java.util.HashMap<>(Map.of("name", name, "namespace", namespace));
        try {
            Object existing = customObjectsApi.getNamespacedCustomObject(
                    "traefik.io", "v1alpha1", namespace, "middlewares", name).execute();
            String resourceVersion = extractResourceVersion(existing);
            if (resourceVersion != null) {
                metadata.put("resourceVersion", resourceVersion);
            }
            Map<String, Object> middleware = Map.of(
                    "apiVersion", "traefik.io/v1alpha1",
                    "kind", "Middleware",
                    "metadata", metadata,
                    "spec", Map.of("stripPrefix", Map.of("prefixes", List.of(path))));
            customObjectsApi.replaceNamespacedCustomObject(
                    "traefik.io", "v1alpha1", namespace, "middlewares", name, middleware).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                Map<String, Object> middleware = Map.of(
                        "apiVersion", "traefik.io/v1alpha1",
                        "kind", "Middleware",
                        "metadata", metadata,
                        "spec", Map.of("stripPrefix", Map.of("prefixes", List.of(path))));
                customObjectsApi.createNamespacedCustomObject(
                        "traefik.io", "v1alpha1", namespace, "middlewares", middleware).execute();
            } else {
                throw e;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String extractResourceVersion(Object obj) {
        if (obj instanceof Map<?, ?> map && map.get("metadata") instanceof Map<?, ?> meta) {
            Object rv = meta.get("resourceVersion");
            return rv != null ? rv.toString() : null;
        }
        return null;
    }

    /**
     * kubectl rollout status와 같은 판정 로직을 그대로 따른다.
     * availableReplicas만 보면 "롤링 업데이트 중 아직 안 죽은 구세대 Pod"가 잠깐
     * Ready로 잡혀서 새 버전이 뜨기도 전에 성공으로 오판할 수 있다 (readinessProbe가
     * 없던 이전 버전의 Pod가 재시작 직후 찰나에 Ready로 카운트되는 식). 그래서
     * observedGeneration/updatedReplicas/replicas/availableReplicas를 모두 확인해서
     * "새 세대가 완전히 자리잡았는지"까지 확인한다.
     */
    public boolean waitForRollout(String namespace, String appName, int expectedReplicas,
                                    int timeoutSeconds) throws ApiException, InterruptedException {
        int waited = 0;
        while (waited < timeoutSeconds) {
            V1Deployment d = appsApi.readNamespacedDeployment(appName, namespace).execute();
            Long generation = d.getMetadata() != null ? d.getMetadata().getGeneration() : null;
            var status = d.getStatus();
            if (status != null && generation != null) {
                Long observedGeneration = status.getObservedGeneration();
                Integer updated = status.getUpdatedReplicas();
                Integer total = status.getReplicas();
                Integer available = status.getAvailableReplicas();
                boolean generationCaughtUp = observedGeneration != null && observedGeneration >= generation;
                boolean updatedEnough = updated != null && updated >= expectedReplicas;
                boolean noLeftoverOldPods = total != null && total.equals(updated);
                boolean availableEnough = available != null && available >= expectedReplicas;
                if (generationCaughtUp && updatedEnough && noLeftoverOldPods && availableEnough) {
                    return true;
                }
            }
            Thread.sleep(3000);
            waited += 3;
        }
        return false;
    }

    /**
     * rollout이 실패했을 때 "왜"를 알아내기 위해 앱 컨테이너의 최근 로그를 긁어온다.
     * DB 인증 실패 같은 실제 원인은 Pod 상태(waiting/terminated reason)만으로는 안 보이고
     * 컨테이너 로그(스택트레이스)에만 남기 때문에, 컨테이너 상태 대신 로그를 우선 조회한다.
     */
    public String getPodFailureLogs(String namespace, String appName) {
        try {
            return fetchPodFailureLogs(namespace, appName);
        } catch (Exception e) {
            // client-java가 인식 못 하는 k3s의 최신 API 필드(예: V1PodStatus.allocatedResources) 때문에
            // Pod 목록/상태 파싱 자체가 깨질 수 있다. 이건 어디까지나 "왜 실패했는지 보여주기 위한
            // 보조 진단"일 뿐이라, 여기서 예외가 나도 원래 실패 사유(FAILED 처리)를 절대 가리면 안 된다.
            return "(진단 도구 자체 오류로 Pod 로그를 가져오지 못함: [" + e.getClass().getSimpleName() + "] " + e.getMessage() + ")";
        }
    }

    /**
     * client-java(24.0.0)가 k3s 1.36의 V1PodStatus에 새로 생긴 필드(allocatedResources 등)를
     * 몰라서 구조화된 Pod 조회 자체가 파싱 예외로 깨진다. 이 진단 전용 경로만은 이미 다른 곳
     * (DockerBuildService의 scp/ssh)에서 쓰던 방식대로 kubectl을 셸아웃해서 우회한다.
     */
    private String fetchPodFailureLogs(String namespace, String appName) throws IOException, InterruptedException {
        String podNames = runKubectl(namespace, "get", "pods", "-l", "app=" + appName,
                "-o", "jsonpath={.items[*].metadata.name}").trim();
        if (podNames.isEmpty()) {
            return "(진단 실패: 해당 라벨의 Pod를 찾을 수 없음)";
        }
        StringBuilder result = new StringBuilder();
        for (String podName : podNames.split("\\s+")) {
            String restartCount = runKubectl(namespace, "get", "pod", podName, "-o",
                    "jsonpath={.status.containerStatuses[?(@.name=='" + appName + "')].restartCount}").trim();
            boolean crashed = !restartCount.isEmpty() && !restartCount.equals("0");

            String log = "";
            if (crashed) {
                // containerd가 재시작 사이에 이전 컨테이너 로그를 이미 정리했을 수 있어서
                // --previous가 실패할 수 있다 (레이스 컨디션). 그러면 현재 로그로 폴백한다.
                log = runKubectl(namespace, "logs", podName, "-c", appName, "--tail=20", "--previous");
                if (log.isBlank() || log.contains("unable to retrieve container logs")) {
                    log = runKubectl(namespace, "logs", podName, "-c", appName, "--tail=20");
                }
            } else {
                log = runKubectl(namespace, "logs", podName, "-c", appName, "--tail=20");
            }
            result.append("--- Pod(").append(podName).append(") 컨테이너(").append(appName)
                    .append(") 최근 로그 ---\n").append(log).append("\n");
        }
        return result.toString();
    }

    private String runKubectl(String namespace, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("kubectl", "--kubeconfig", kubeconfigPath, "-n", namespace));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        process.waitFor();
        return output;
    }

    /**
     * MyBatis 등 자동 테이블 생성이 안 되는 프레임워크를 위해, 사용자가 올린 DB 초기화 SQL 파일을
     * 이 배포 전용 DB 파드({appName}-mysql) 안에서 한 번 실행한다(kubectl exec -i로 stdin에 SQL을 흘려보냄).
     */
    public String runSchemaSql(String namespace, String appName, String engine, String databaseName,
                                String password, java.nio.file.Path sqlFile) throws IOException, InterruptedException {
        String app = appName + "-mysql";
        String podName = runKubectl(namespace, "get", "pods", "-l", "app=" + app,
                "-o", "jsonpath={.items[0].metadata.name}").trim();
        if (podName.isEmpty()) {
            throw new IllegalStateException("DB 초기화 SQL 실행 실패: " + app + " Pod를 찾을 수 없음");
        }

        List<String> command = new java.util.ArrayList<>(List.of("kubectl", "--kubeconfig", kubeconfigPath,
                "-n", namespace, "exec", "-i", podName, "--"));
        command.addAll(List.of("mysql", "-uroot", "-p" + password, databaseName));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectInput(sqlFile.toFile());
        Process process = pb.start();
        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("DB 초기화 SQL 실행 실패(exit=" + exitCode + "): " + output);
        }
        return output;
    }
}
