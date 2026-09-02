package com.talentserv.blink.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.dto.WorkspaceInventoryResponse;
import com.talentserv.blink.dto.WorkspaceStatusResponse;
import com.talentserv.blink.error.ApiException;

import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

@Service
public class S3WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(S3WorkspaceService.class);

    private final BlinkProperties properties;
    private final ExecutorService uploads = Executors.newFixedThreadPool(16);
    private final ExecutorService provisioner = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<String, ProvisionJob> jobs = new ConcurrentHashMap<>();
    private volatile S3Client client;

    public S3WorkspaceService(BlinkProperties properties) {
        this.properties = properties;
    }

    @PreDestroy
    void close() {
        provisioner.shutdownNow();
        uploads.shutdown();
        S3Client current = client;
        if (current != null) {
            current.close();
        }
    }

    public boolean enabled() {
        return properties.s3Enabled();
    }

    /** Start copying the template without blocking the HTTP request. */
    public void provisionAsync(String projectName) {
        if (!enabled() || projectName == null || projectName.isBlank()) {
            return;
        }
        ensureStarted(projectName, false);
    }

    /** Block until the template copy finishes. Used by download. */
    public String provision(String projectName) {
        requireEnabled();
        ProvisionJob job = ensureStarted(projectName, true);
        await(job);
        if (job.status.get() == WorkspaceStatus.FAILED) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not create the S3 workspace.");
        }
        return WorkspaceNames.folder(projectName);
    }

    public String status(String projectName) {
        WorkspaceStatusResponse progress = progress(projectName);
        return progress == null ? null : progress.status();
    }

    public WorkspaceStatusResponse progress(String projectName) {
        if (!enabled() || projectName == null || projectName.isBlank()) {
            return null;
        }
        String folder = WorkspaceNames.folder(projectName);
        ProvisionJob job = jobs.get(folder);
        if (job != null) {
            WorkspaceStatus state = job.status.get();
            int copied = job.filesCopied.get();
            int total = job.filesTotal.get();
            return new WorkspaceStatusResponse(
                    folder,
                    state.json(),
                    copied,
                    total,
                    percent(state, copied, total),
                    state != WorkspaceStatus.FAILED
            );
        }
        boolean exists = hasPrefix(WorkspaceNames.key(projectName, "automation_sdlc/"));
        return new WorkspaceStatusResponse(folder, exists ? "ready" : null, exists ? 1 : 0, exists ? 1 : 0, exists ? 100 : 0, exists);
    }

    private static int percent(WorkspaceStatus status, int copied, int total) {
        if (status == WorkspaceStatus.READY) {
            return 100;
        }
        if (status == WorkspaceStatus.FAILED || total <= 0) {
            return 0;
        }
        return Math.min(99, (int) Math.round(copied * 100.0 / total));
    }

    public WorkspaceInventoryResponse inspect(String projectName) {
        requireEnabled();
        String folder = WorkspaceNames.folder(projectName);
        String prefix = folder + "/";
        log.info("S3 inspect listing objects prefix={}", prefix);
        List<ListedObject> objects = new ArrayList<>();
        String token = null;
        do {
            var response = client().listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(properties.getS3BucketName().trim())
                    .prefix(prefix)
                    .continuationToken(token)
                    .build());
            for (S3Object object : response.contents()) {
                if (object.key() == null || object.key().endsWith("/")) {
                    continue;
                }
                objects.add(new ListedObject(object.key(), object.size() == null ? 0L : object.size()));
            }
            token = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (token != null);
        log.info("S3 inspect listed {} objects prefix={}", objects.size(), prefix);
        return summarize(
                properties.getS3BucketName().trim(),
                folder,
                WorkspaceNames.publicUrl(properties.getS3PublicBaseUrl(), projectName),
                status(projectName),
                objects
        );
    }

    record ListedObject(String key, long size) {
    }

    static WorkspaceInventoryResponse summarize(
            String bucket,
            String folder,
            String url,
            String status,
            List<ListedObject> objects
    ) {
        String prefix = folder + "/";
        Map<String, long[]> tops = new LinkedHashMap<>();
        List<String> samples = new ArrayList<>();
        long totalBytes = 0;
        for (ListedObject object : objects) {
            if (object.key() == null || object.key().endsWith("/")) {
                continue;
            }
            totalBytes += object.size();
            String relative = object.key().startsWith(prefix) ? object.key().substring(prefix.length()) : object.key();
            if (relative.isBlank()) {
                continue;
            }
            String top = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : relative;
            long[] acc = tops.computeIfAbsent(top, ignored -> new long[2]);
            acc[0] += 1;
            acc[1] += object.size();
            if (samples.size() < 40) {
                samples.add(relative);
            }
        }
        List<WorkspaceInventoryResponse.TopLevel> topLevel = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : tops.entrySet()) {
            topLevel.add(new WorkspaceInventoryResponse.TopLevel(
                    entry.getKey(),
                    (int) entry.getValue()[0],
                    entry.getValue()[1]
            ));
        }
        topLevel.sort((a, b) -> Integer.compare(b.files(), a.files()));
        return new WorkspaceInventoryResponse(
                bucket,
                folder,
                url,
                status,
                !objects.isEmpty(),
                objects.size(),
                totalBytes,
                List.copyOf(topLevel),
                List.copyOf(samples)
        );
    }

    private ProvisionJob ensureStarted(String projectName, boolean retryFailed) {
        String folder = WorkspaceNames.folder(projectName);
        return jobs.compute(folder, (key, existing) -> {
            if (existing != null && existing.status.get() == WorkspaceStatus.PREPARING
                    && existing.future != null && !existing.future.isDone()) {
                return existing;
            }
            if (existing != null && existing.status.get() == WorkspaceStatus.READY) {
                return existing;
            }
            if (existing != null && existing.status.get() == WorkspaceStatus.FAILED && !retryFailed) {
                return existing;
            }
            ProvisionJob job = new ProvisionJob();
            job.future = provisioner.submit(() -> runProvision(projectName, job));
            return job;
        });
    }

    private void runProvision(String projectName, ProvisionJob job) {
        String folder = WorkspaceNames.folder(projectName);
        try {
            putBytes(WorkspaceNames.key(projectName, ".blink-workspace.json"), workspaceManifest(projectName), "application/json");
            if (hasPrefix(WorkspaceNames.key(projectName, "automation_sdlc/"))) {
                log.info("S3 workspace already has automation_sdlc folder={}", folder);
                job.filesTotal.set(1);
                job.filesCopied.set(1);
                job.status.set(WorkspaceStatus.READY);
                return;
            }
            Path source = resolveAutomationSdlc();
            log.info("S3 uploading automation_sdlc into folder={} from {}", folder, source);
            uploadTree(source, WorkspaceNames.key(projectName, "automation_sdlc"), job.filesCopied, job.filesTotal);
            log.info("Provisioned S3 workspace folder={} files from {}", folder, source);
            job.status.set(WorkspaceStatus.READY);
        } catch (Exception ex) {
            job.status.set(WorkspaceStatus.FAILED);
            log.warn("S3 workspace provision failed: {}", ex.toString());
        }
    }

    private static void await(ProvisionJob job) {
        if (job == null || job.future == null) {
            return;
        }
        try {
            job.future.get(3, TimeUnit.MINUTES);
        } catch (TimeoutException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not create the S3 workspace.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not create the S3 workspace.");
        } catch (ExecutionException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not create the S3 workspace.");
        }
    }

    private enum WorkspaceStatus {
        PREPARING,
        READY,
        FAILED;

        String json() {
            return name().toLowerCase();
        }
    }

    private static final class ProvisionJob {
        private final AtomicReference<WorkspaceStatus> status = new AtomicReference<>(WorkspaceStatus.PREPARING);
        private final AtomicInteger filesCopied = new AtomicInteger();
        private final AtomicInteger filesTotal = new AtomicInteger();
        private volatile Future<?> future;
    }

    public void putRequirement(String projectName, String markdown) {
        requireEnabled();
        String body = markdown == null || markdown.isBlank() ? "# requirement\n" : markdown;
        putBytes(WorkspaceNames.key(projectName, "requirement.md"), body.getBytes(StandardCharsets.UTF_8), "text/markdown");
    }

    public void putCursorOverlay(String projectName, List<ZipPackageService.OverlayFile> overlayFiles) {
        requireEnabled();
        boolean wrote = false;
        if (overlayFiles != null) {
            for (ZipPackageService.OverlayFile file : overlayFiles) {
                String relative = ZipPackageService.sanitizeOverlayPath(file.path());
                if (relative == null) {
                    continue;
                }
                String content = file.content() == null ? "" : file.content();
                putBytes(
                        WorkspaceNames.key(projectName, relative),
                        content.getBytes(StandardCharsets.UTF_8),
                        "text/plain"
                );
                wrote = true;
            }
        }
        if (!wrote) {
            putBytes(WorkspaceNames.key(projectName, ".cursor/.keep"), new byte[0], "application/octet-stream");
        }
    }

    public ZipPackageService.WorkspaceBundle zipWorkspace(String projectName) {
        requireEnabled();
        String folder = WorkspaceNames.folder(projectName);
        try {
            log.info("S3 zip listing objects prefix={}/", folder);
            Map<String, byte[]> objects = listPrefix(folder + "/");
            log.info("S3 zip listed {} objects for {}", objects.size(), folder);
            if (objects.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "S3 workspace is empty. Save the project first.");
            }
            return zipObjects(folder, objects);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("S3 workspace zip failed: {}", ex.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not download the S3 workspace.");
        }
    }

    static ZipPackageService.WorkspaceBundle zipObjects(String folder, Map<String, byte[]> objects) throws IOException {
        List<ZipPackageService.WorkspaceEntry> structure = new ArrayList<>();
        structure.add(new ZipPackageService.WorkspaceEntry("automation_sdlc", "directory"));
        boolean cursor = objects.keySet().stream().anyMatch(key -> key.contains("/.cursor/"));
        if (cursor) {
            structure.add(new ZipPackageService.WorkspaceEntry(".cursor", "directory"));
        }
        if (objects.containsKey(folder + "/requirement.md") || objects.containsKey("requirement.md")) {
            structure.add(new ZipPackageService.WorkspaceEntry("requirement.md", "file"));
        }
        AtomicInteger files = new AtomicInteger();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(folder + "/"));
            zip.closeEntry();
            for (Map.Entry<String, byte[]> entry : objects.entrySet()) {
                String key = entry.getKey();
                if (key.endsWith("/")) {
                    continue;
                }
                String zipName = key.startsWith(folder + "/") ? key : folder + "/" + key.replaceFirst("^/+", "");
                zip.putNextEntry(new ZipEntry(zipName));
                zip.write(entry.getValue() == null ? new byte[0] : entry.getValue());
                zip.closeEntry();
                files.incrementAndGet();
            }
        }
        return new ZipPackageService.WorkspaceBundle(buffer.toByteArray(), folder + ".zip", List.copyOf(structure), files.get());
    }

    private Path resolveAutomationSdlc() throws IOException, InterruptedException {
        Path local = Path.of(properties.getAutomationSdlcPath() == null ? "" : properties.getAutomationSdlcPath())
                .toAbsolutePath()
                .normalize();
        if (Files.isDirectory(local)) {
            return local;
        }
        String gitUrl = properties.getAutomationSdlcGitUrl();
        if (gitUrl != null && !gitUrl.isBlank()) {
            return cloneAutomationSdlc(gitUrl.trim());
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Could not clone or find automation_sdlc.");
    }

    private Path cloneAutomationSdlc(String gitUrl) throws IOException, InterruptedException {
        Path temp = Files.createTempDirectory("blink-automation-sdlc-");
        Process process = new ProcessBuilder("git", "clone", "--depth", "1", gitUrl, temp.toString())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Timed out cloning automation_sdlc.");
        }
        if (process.exitValue() != 0) {
            log.warn("git clone failed: {}", output);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not clone automation_sdlc.");
        }
        return temp;
    }

    private void uploadTree(Path root, String keyPrefix) throws Exception {
        uploadTree(root, keyPrefix, null, null);
    }

    private void uploadTree(Path root, String keyPrefix, AtomicInteger copied, AtomicInteger total) throws Exception {
        List<Callable<Void>> tasks = new ArrayList<>();
        LinkedHashSet<String> skippedDirs = new LinkedHashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName().toString();
                if (FrameworkKitFilter.skipDirectory(name)) {
                    skippedDirs.add(name);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (FrameworkKitFilter.skipFile(name) || attrs.size() > 8_000_000) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                String key = keyPrefix + "/" + relative;
                tasks.add(() -> {
                    putBytes(key, Files.readAllBytes(file), contentType(name));
                    if (copied != null) {
                        copied.incrementAndGet();
                    }
                    return null;
                });
                return FileVisitResult.CONTINUE;
            }
        });
        if (total != null) {
            total.set(Math.max(tasks.size(), 1));
        }
        log.info(
                "S3 upload queued {} files prefix={} skippedDirs={}",
                tasks.size(),
                keyPrefix,
                skippedDirs.isEmpty() ? "none" : String.join(",", skippedDirs)
        );
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(uploads.submit(task));
        }
        for (Future<Void> future : futures) {
            future.get(3, TimeUnit.MINUTES);
        }
        if (tasks.isEmpty()) {
            putBytes(keyPrefix + "/README.md", "# automation_sdlc\n".getBytes(StandardCharsets.UTF_8), "text/markdown");
            if (copied != null) {
                copied.incrementAndGet();
            }
        }
    }

    private boolean hasPrefix(String prefix) {
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(properties.getS3BucketName().trim())
                .prefix(prefix)
                .maxKeys(1)
                .build();
        return !client().listObjectsV2(request).contents().isEmpty();
    }

    private Map<String, byte[]> listPrefix(String prefix) {
        Map<String, byte[]> objects = new LinkedHashMap<>();
        String token = null;
        do {
            var response = client().listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(properties.getS3BucketName().trim())
                    .prefix(prefix)
                    .continuationToken(token)
                    .build());
            for (S3Object object : response.contents()) {
                if (object.key().endsWith("/")) {
                    continue;
                }
                objects.put(object.key(), getBytes(object.key()));
                if (objects.size() % 100 == 0) {
                    log.info("S3 zip downloaded {} objects", objects.size());
                }
            }
            token = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (token != null);
        return objects;
    }

    private byte[] getBytes(String key) {
        try (InputStream in = client().getObject(GetObjectRequest.builder()
                .bucket(properties.getS3BucketName().trim())
                .key(key)
                .build())) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not read S3 object.");
        }
    }

    private void putBytes(String key, byte[] body, String contentType) {
        client().putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getS3BucketName().trim())
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(body == null ? new byte[0] : body)
        );
    }

    private byte[] workspaceManifest(String projectName) {
        String folder = WorkspaceNames.folder(projectName);
        String json = """
                {"projectName":%s,"workspace":%s}
                """.formatted(jsonString(projectName), jsonString(folder));
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String jsonString(String value) {
        String raw = value == null ? "" : value;
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "text/yaml";
        if (lower.endsWith(".ts") || lower.endsWith(".js") || lower.endsWith(".py")) return "text/plain";
        if (lower.endsWith(".java")) return "text/plain";
        return "application/octet-stream";
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "S3 workspace storage is not configured.");
        }
    }

    private S3Client client() {
        S3Client current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                client = S3Client.builder()
                        .region(Region.of(properties.getAwsRegion().trim()))
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        properties.getAwsAccessKeyId().trim(),
                                        properties.getAwsSecretAccessKey().trim()
                                )
                        ))
                        .httpClientBuilder(UrlConnectionHttpClient.builder()
                                .connectionTimeout(Duration.ofSeconds(5))
                                .socketTimeout(Duration.ofSeconds(60)))
                        .build();
            }
            return client;
        }
    }
}
