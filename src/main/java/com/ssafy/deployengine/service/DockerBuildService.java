package com.ssafy.deployengine.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 아티팩트를 컨테이너 이미지로 빌드해 각 워커(현재 E206)에 전달한다. 사용자는 Dockerfile을
 * 올리지 않는다 - 선택한 스택(언어+프레임워크)에 맞춰 플랫폼이 표준 Dockerfile을 자동 생성한다:
 *  - SPRING_JPA / SPRING_MYBATIS : .jar 산출물 하나 → 표준 Java 실행 Dockerfile
 *  - DJANGO / FASTAPI / EXPRESS  : 소스 zip(의존성 명세 포함) → 표준 Python/Node 빌드 Dockerfile
 * 실제 docker build는 컨트롤 플레인(t3.small)이 아니라 빌드 호스트(E206)에서 SSH로 원격 수행한다.
 */
@Service
public class DockerBuildService {

    // 소스 zip 압축 해제 상한(zip-bomb 방지)
    private static final long MAX_TOTAL_BYTES = 500L * 1024 * 1024;
    private static final int MAX_ENTRIES = 50000;

    @Value("${deploy.ssh-key-path}")
    private String sshKeyPath;

    @Value("${deploy.worker-hosts}")
    private String workerHostsCsv;

    private String buildHost() {
        return workerHostsCsv.split(",")[0].trim();
    }

    public void buildImage(String workDir, String fileUrl, String imageTag, String stack,
                            String runtimeVersion, int internalPort, LogSink log) throws Exception {
        String host = buildHost();
        String remoteDir = "/tmp/" + Path.of(workDir).getFileName();
        Path localDir = Path.of(workDir);
        Files.createDirectories(localDir);

        if ("DJANGO".equals(stack) || "FASTAPI".equals(stack) || "EXPRESS".equals(stack)) {
            buildFromSourceZip(host, remoteDir, localDir, fileUrl, imageTag, stack, runtimeVersion, internalPort, log);
        } else {
            buildFromJar(host, remoteDir, localDir, fileUrl, imageTag, runtimeVersion, log);
        }
    }

    /** SPRING_JPA / SPRING_MYBATIS: .jar 하나만 받아 표준 Java 실행 Dockerfile을 생성해 빌드한다. */
    private void buildFromJar(String host, String remoteDir, Path localDir, String fileUrl, String imageTag,
                               String runtimeVersion, LogSink log) throws Exception {
        Path localJar = localDir.resolve("app.jar");
        download(fileUrl, localJar);
        log.line("jar 다운로드 완료: " + fileUrl);

        String jreTag = (runtimeVersion == null || runtimeVersion.isBlank() ? "17" : runtimeVersion.trim()) + "-jre";
        String dockerfile = """
                FROM eclipse-temurin:%s
                WORKDIR /app
                COPY app.jar app.jar
                ENTRYPOINT ["java", "-jar", "app.jar"]
                """.formatted(jreTag);
        Path localDockerfile = localDir.resolve("Dockerfile");
        Files.writeString(localDockerfile, dockerfile);

        ssh(log, host, "mkdir -p " + remoteDir);
        scp(log, localJar.toString(), "ubuntu@" + host + ":" + remoteDir + "/app.jar");
        scp(log, localDockerfile.toString(), "ubuntu@" + host + ":" + remoteDir + "/Dockerfile");
        ssh(log, host, "sudo docker build -t " + imageTag + " " + remoteDir);
    }

    /**
     * DJANGO / EXPRESS: 소스 zip(requirements.txt / package.json 등 의존성 명세 포함)을 받아
     * 스택에 맞는 표준 Dockerfile을 플랫폼이 직접 생성해 컨텍스트에 써넣은 뒤 빌드한다.
     */
    private void buildFromSourceZip(String host, String remoteDir, Path localDir, String fileUrl, String imageTag,
                                     String stack, String runtimeVersion, int internalPort, LogSink log)
            throws Exception {
        Path zipPath = localDir.resolve("artifact.zip");
        download(fileUrl, zipPath);
        log.line("소스 아티팩트(zip) 다운로드 완료: " + fileUrl);

        Path extractDir = Files.createDirectories(localDir.resolve("ctx"));
        unzip(zipPath, extractDir);

        // 프로젝트 전체가 zip 루트가 아니라 한 겹 폴더(예: 프로젝트명/) 안에 있는 흔한 경우 보정.
        Path contextRoot = resolveSourceRoot(extractDir);
        String dockerfile;
        if ("DJANGO".equals(stack)) {
            dockerfile = djangoDockerfile(runtimeVersion, internalPort);
        } else if ("FASTAPI".equals(stack)) {
            dockerfile = fastApiDockerfile(runtimeVersion, internalPort);
        } else {
            dockerfile = expressDockerfile(runtimeVersion, internalPort);
        }
        Files.writeString(contextRoot.resolve("Dockerfile"), dockerfile);
        log.line(stack + " 표준 Dockerfile 생성 완료");

        // Windows에서 압축한 zip은 유닉스 실행 권한 비트가 없어서 전부 실행 불가(644)로 풀린다.
        // manage.py 등 실행이 필요한 파일이 있을 수 있어 컨텍스트 전체에 +x를 부여해둔다(무해함).
        runAndStream(log, null, "chmod", "-R", "+x", contextRoot.toString());

        // scp -r로 컨텍스트 폴더를 통째로 올린 뒤 그 자리에서 docker build.
        // remoteDir가 없을 때 scp -r가 컨텍스트 내용을 remoteDir로 복사하므로 먼저 지운다.
        ssh(log, host, "rm -rf " + remoteDir);
        runAndStream(log, null, "scp", "-r", "-i", sshKeyPath, "-o", "StrictHostKeyChecking=no",
                contextRoot.toString(), "ubuntu@" + host + ":" + remoteDir);
        ssh(log, host, "sudo docker build -t " + imageTag + " " + remoteDir);
    }

    private String djangoDockerfile(String runtimeVersion, int internalPort) {
        String version = (runtimeVersion == null || runtimeVersion.isBlank()) ? "3.11" : runtimeVersion.trim();
        return """
                FROM python:%s-slim
                WORKDIR /app
                COPY . .
                RUN pip install --no-cache-dir -r requirements.txt
                EXPOSE %d
                CMD ["sh", "-c", "python manage.py migrate --noinput && python manage.py runserver 0.0.0.0:%d"]
                """.formatted(version, internalPort, internalPort);
    }

    private String fastApiDockerfile(String runtimeVersion, int internalPort) {
        String version = (runtimeVersion == null || runtimeVersion.isBlank()) ? "3.11" : runtimeVersion.trim();
        return """
                FROM python:%s-slim
                WORKDIR /app
                COPY . .
                RUN pip install --no-cache-dir -r requirements.txt
                EXPOSE %d
                CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "%d"]
                """.formatted(version, internalPort, internalPort);
    }

    private String expressDockerfile(String runtimeVersion, int internalPort) {
        String version = (runtimeVersion == null || runtimeVersion.isBlank()) ? "20" : runtimeVersion.trim();
        return """
                FROM node:%s-alpine
                WORKDIR /app
                COPY . .
                RUN npm install --omit=dev
                EXPOSE %d
                ENV PORT=%d
                CMD ["npm", "start"]
                """.formatted(version, internalPort, internalPort);
    }

    /** 소스 전체가 zip 루트가 아니라 유일한 하위 폴더 안에 들어있는 흔한 경우, 그 폴더를 컨텍스트로 쓴다. */
    private Path resolveSourceRoot(Path extractDir) throws IOException {
        try (var stream = Files.list(extractDir)) {
            List<Path> entries = stream.toList();
            if (entries.size() == 1 && Files.isDirectory(entries.get(0))) {
                return entries.get(0);
            }
        }
        return extractDir;
    }

    /** presigned URL에서 파일을 로컬로 내려받는다 - DB 초기화 SQL처럼 이미지 빌드와 무관한 다운로드에도 재사용. */
    public void downloadFile(String fileUrl, Path target) {
        download(fileUrl, target);
    }

    private void download(String fileUrl, Path target) {
        try (var in = URI.create(fileUrl).toURL().openStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.FileNotFoundException e) {
            throw new IllegalStateException("아티팩트 다운로드 실패: URL을 찾을 수 없음(404) - " + fileUrl);
        } catch (IOException e) {
            throw new IllegalStateException("아티팩트 다운로드 실패: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage() + " (url=" + fileUrl + ")", e);
        }
    }

    private void unzip(Path zip, Path targetDir) throws IOException {
        Path normalizedTarget = targetDir.normalize();
        long total = 0;
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    throw new IOException("zip 엔트리 수 초과(zip-bomb 방지)");
                }
                // Windows(Compress-Archive) zip은 역슬래시 구분자를 쓰기도 해서 '/'로 정규화.
                String entryName = entry.getName().replace('\\', '/');
                Path resolved = targetDir.resolve(entryName).normalize();
                if (!resolved.startsWith(normalizedTarget)) {
                    throw new IOException("zip-slip 감지, 거부: " + entry.getName());
                }
                // entry.isDirectory()는 원본 이름이 '/'로 끝날 때만 true를 반환한다. Windows
                // Compress-Archive는 디렉터리 엔트리를 역슬래시로 끝내므로(entry.getName()
                // 기준) 정규화된 이름으로 다시 한번 확인해야 디렉터리를 파일로 잘못 풀지 않는다.
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

    public void transferImage(String workDir, String imageTag, LogSink log) throws Exception {
        String buildHost = buildHost();

        for (String host : workerHostsCsv.split(",")) {
            host = host.trim();
            log.line("워커(" + host + ")로 이미지 전달 중...");

            if (host.equals(buildHost)) {
                ssh(log, host, "sudo sh -c 'docker save " + imageTag + " | k3s ctr images import -'");
            } else {
                Path tarPath = Path.of(workDir, "image.tar");
                ssh(log, buildHost, "sudo docker save " + imageTag + " -o /tmp/image-transfer.tar");
                scp(log, "ubuntu@" + buildHost + ":/tmp/image-transfer.tar", tarPath.toString());
                scp(log, tarPath.toString(), "ubuntu@" + host + ":/tmp/image.tar");
                ssh(log, host, "sudo k3s ctr images import /tmp/image.tar && rm /tmp/image.tar");
            }
        }

        String remoteDir = "/tmp/" + Path.of(workDir).getFileName();
        ssh(log, buildHost, "rm -rf " + remoteDir + " /tmp/image-transfer.tar");
    }

    private void ssh(LogSink log, String host, String remoteCommand) throws IOException, InterruptedException {
        runAndStream(log, null, "ssh", "-i", sshKeyPath, "-o", "StrictHostKeyChecking=no",
                "ubuntu@" + host, remoteCommand);
    }

    private void scp(LogSink log, String from, String to) throws IOException, InterruptedException {
        runAndStream(log, null, "scp", "-i", sshKeyPath, "-o", "StrictHostKeyChecking=no", from, to);
    }

    private void runAndStream(LogSink log, java.io.File dir, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        if (dir != null) {
            pb.directory(dir);
        }
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.line(line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("명령 실패(exit=" + exitCode + "): " + String.join(" ", command));
        }
    }

    /** 로그 한 줄을 어디로 보낼지는 호출하는 쪽(DeploymentProcessor)이 결정 */
    public interface LogSink {
        void line(String message);
    }
}
