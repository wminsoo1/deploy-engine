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
 * 프론트엔드(.zip 정적 빌드 결과물)를 S3 정적 호스팅으로 배포한다.
 * S3 아티팩트(zip) 다운로드 → 안전 압축해제 → 공개 버킷(ssafy-deploy-frontend)의 {slug}/ 로 업로드.
 * 다운/해제/업로드가 전부 컨트롤 플레인에서 돌고, 이 서버 IAM 역할이 GetObject/PutObject 권한을 가지므로
 * aws CLI를 직접 사용한다(백엔드처럼 Docker로 빌드할 필요가 없다 - 사용자가 이미 빌드해서 올린 정적 파일).
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

    /** 프론트 zip을 배포하고 접속용 정적 웹 URL을 반환한다. */
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

    private String uploadWebRoot(Path webRoot, String slug, LogSink log) throws IOException, InterruptedException {
        // 같은 slug로 다른 프로젝트를 재배포하면(학번은 고정이라 slug가 항상 같음), 새 산출물에
        // 없는 예전 파일이 그대로 남아있었다("index.html이 최신 자산을 가리키니 무해"라고 여겼지만,
        // 실제로는 완전히 지운 게 아니라서 사용자가 혼란스러워했다) - 업로드 전에 기존 걸 먼저 지운다.
        // ListBucket/DeleteObject 권한이 아직 없는 컨트롤 플레인 IAM 역할도 있을 수 있어서,
        // 권한이 없으면 예전처럼 orphan 파일이 남는 정도로만 그치고 배포 자체는 계속 진행한다
        // (권한이 추가되면 그때부터 자동으로 완전 삭제가 적용됨).
        try {
            log.line("기존 S3 파일 정리 중: s3://" + frontendBucket + "/" + slug + "/");
            runAws(log, "s3", "rm", "s3://" + frontendBucket + "/" + slug + "/",
                    "--recursive", "--region", region, "--only-show-errors");
        } catch (IOException e) {
            log.line("  기존 파일 정리 실패(권한 부족 가능, 무시하고 계속 진행): " + e.getMessage());
        }

        // cp --recursive는 로컬 파일을 PutObject로 올리기만 하므로 PutObject 권한만 있으면 된다.
        log.line("S3 정적 호스팅으로 업로드: s3://" + frontendBucket + "/" + slug + "/");
        runAws(log, "s3", "cp", webRoot.toString(), "s3://" + frontendBucket + "/" + slug + "/",
                "--recursive", "--region", region, "--only-show-errors");

        String url = "http://" + frontendBucket + ".s3-website." + region + ".amazonaws.com/" + slug + "/";
        log.line("프론트 배포 완료: " + url);
        return url;
    }

    /**
     * zip 루트에 index.html이 있으면 그대로, 없고 하위 폴더가 유일하면 그 폴더를 계속
     * 따라 내려간다(빌드 도구에 따라 산출물이 한두 겹 폴더 안에 들어있는 경우를 보정).
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
