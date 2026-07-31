package com.ssafy.deployengine.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.ssafy.deployengine.service.DockerBuildService.LogSink;

/**
 * 프론트엔드를 S3 정적 호스팅으로 배포한다. 두 갈래가 있다:
 *  - PLAIN(이미 빌드된 정적 zip): S3 아티팩트 다운로드 → 안전 압축해제 → 업로드. Docker 불필요
 *    (사용자가 이미 빌드해서 올린 정적 파일이라 aws CLI만으로 충분).
 *  - REACT/VUE/ANGULAR/SVELTE(소스 zip): DockerBuildService로 빌드만 컨테이너에서 수행해
 *    정적 산출물만 뽑아온 뒤, 그 결과를 PLAIN과 동일한 방식으로 업로드한다.
 * 어느 쪽이든 최종 업로드 대상(webRoot)만 찾으면 그 뒤는 완전히 같아서 uploadWebRoot()로 공용화했다.
 */
@Service
public class FrontendDeployService {

    // zip-bomb 방지: 총 해제 용량/엔트리 수 상한.
    private static final long MAX_TOTAL_BYTES = 300L * 1024 * 1024; // 300MB
    private static final int MAX_ENTRIES = 20000;

    @Value("${deploy.aws-cli-path:/usr/bin/aws}")
    private String awsCliPath;

    @Value("${deploy.aws-region:ap-northeast-2}")
    private String region;

    @Value("${deploy.frontend.bucket:ssafy-deploy-frontend}")
    private String frontendBucket;

    private final DockerBuildService dockerBuildService;

    public FrontendDeployService(DockerBuildService dockerBuildService) {
        this.dockerBuildService = dockerBuildService;
    }

    /** PLAIN: 이미 빌드된 정적 zip을 그대로 올린다. */
    public String deployFrontend(String artifactBucket, String objectKey, String slug, LogSink log)
            throws IOException, InterruptedException {
        Path work = Files.createTempDirectory("frontend-" + slug + "-");
        Path zipPath = work.resolve("artifact.zip");
        Path extractDir = Files.createDirectories(work.resolve("site"));
        try {
            log.line("프론트 아티팩트 다운로드: s3://" + artifactBucket + "/" + objectKey);
            runAws(log, "s3", "cp", "s3://" + artifactBucket + "/" + objectKey, zipPath.toString(),
                    "--region", region, "--only-show-errors");

            log.line("압축 해제 중...");
            unzip(zipPath, extractDir);

            Path webRoot = resolveWebRoot(extractDir);
            return uploadWebRoot(webRoot, slug, log);
        } finally {
            deleteRecursively(work);
        }
    }

    /**
     * REACT/VUE/ANGULAR/SVELTE: 소스 zip을 DockerBuildService로 빌드해 정적 산출물만
     * 뽑아온 뒤 업로드한다. fileUrl은 백엔드 아티팩트와 동일하게 presigned URL을 쓴다
     * (buildStaticSite가 빌드 호스트로 컨텍스트를 옮기는 방식이 백엔드 소스 빌드와 같기 때문).
     */
    public String buildAndDeployFrontend(String fileUrl, String slug, String runtimeVersion, LogSink log)
            throws Exception {
        String workDir = "/tmp/frontend-build-" + slug + "-" + System.nanoTime();
        Path builtDir = dockerBuildService.buildStaticSite(workDir, fileUrl,
                "frontend-" + slug + ":build", runtimeVersion, log);
        try {
            Path webRoot = resolveWebRoot(builtDir);
            return uploadWebRoot(webRoot, slug, log);
        } finally {
            deleteRecursively(Path.of(workDir));
        }
    }

    private String uploadWebRoot(Path webRoot, String slug, LogSink log) throws IOException, InterruptedException {
        // cp --recursive는 로컬 파일을 PutObject로 올리기만 하므로 PutObject 권한만 있으면 된다.
        // (sync는 대상 비교에 ListBucket, --delete엔 DeleteObject가 필요해 권한을 더 요구한다.)
        // 대신 이전 배포의 orphan 파일은 남는다(index.html이 최신 자산을 가리키므로 동작엔 무해).
        log.line("S3 정적 호스팅으로 업로드: s3://" + frontendBucket + "/" + slug + "/");
        runAws(log, "s3", "cp", webRoot.toString(), "s3://" + frontendBucket + "/" + slug + "/",
                "--recursive", "--region", region, "--only-show-errors");

        String url = "http://" + frontendBucket + ".s3-website." + region + ".amazonaws.com/" + slug + "/";
        log.line("프론트 배포 완료: " + url);
        return url;
    }

    /**
     * index.html을 찾을 때까지 "하위 폴더가 딱 하나뿐인" 경우를 계속 따라 내려간다.
     * Angular는 dist/{프로젝트명}/browser/처럼 두 겹 들어가는 경우가 있어 한 겹만 보던
     * 기존 로직으론 부족해서 재귀로 확장했다(React/Vue/Svelte는 보통 0~1겹이라 그대로 통과).
     */
    private Path resolveWebRoot(Path dir) throws IOException {
        if (Files.exists(dir.resolve("index.html"))) {
            return dir;
        }
        try (var stream = Files.list(dir)) {
            List<Path> entries = stream.toList();
            if (entries.size() == 1 && Files.isDirectory(entries.get(0))) {
                return resolveWebRoot(entries.get(0));
            }
        }
        // 못 찾으면 그대로 둔다(업로드는 되지만 index.html 위치는 산출물 구성에 따름).
        return dir;
    }

    private void unzip(Path zip, Path targetDir) throws IOException {
        Path normalizedTarget = targetDir.normalize();
        long total = 0;
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    throw new IOException("zip 엔트리 수 초과(zip-bomb 방지): " + count);
                }
                // Windows(Compress-Archive)에서 만든 zip은 엔트리 구분자로 역슬래시(\)를 쓰기도 한다.
                // 그대로 두면 Linux/Java에서 "assets\index.js"가 폴더가 아닌 한 파일명으로 취급돼
                // assets/ 하위구조가 깨진다(→ 브라우저가 /assets/..로 요청 시 404). '/'로 정규화한다.
                String entryName = entry.getName().replace('\\', '/');
                Path resolved = targetDir.resolve(entryName).normalize();
                // zip-slip: 압축 대상 경로가 targetDir 밖으로 나가는 엔트리(../ 등) 차단.
                if (!resolved.startsWith(normalizedTarget)) {
                    throw new IOException("zip-slip 감지, 거부: " + entry.getName());
                }
                // entry.isDirectory()는 원본 이름이 '/'로 끝날 때만 true라서, Windows처럼
                // 디렉터리 엔트리를 역슬래시로 끝내는 zip은 정규화된 이름으로 다시 확인해야 한다.
                if (entry.isDirectory() || entryName.endsWith("/")) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    try (OutputStream os = Files.newOutputStream(resolved)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            total += n;
                            if (total > MAX_TOTAL_BYTES) {
                                throw new IOException("압축 해제 총량 초과(zip-bomb 방지)");
                            }
                            os.write(buf, 0, n);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void runAws(LogSink log, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(awsCliPath);
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                output.append(line).append('\n');
                log.line("  " + line);
            }
        }
        int code = process.waitFor();
        if (code != 0) {
            throw new IOException("aws " + String.join(" ", args) + " 실패(exit=" + code + "): " + output);
        }
    }

    private void deleteRecursively(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 임시파일 정리 실패는 무시
                }
            });
        } catch (IOException ignored) {
            // 정리 실패는 배포 결과에 영향 주지 않음
        }
    }
}
