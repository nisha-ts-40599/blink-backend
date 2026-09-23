package com.talentserv.blink.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.dto.FigmaDesignBindingRequest;
import com.talentserv.blink.dto.FigmaDesignBindingResponse;
import com.talentserv.blink.dto.FigmaDesignChange;
import com.talentserv.blink.dto.FigmaFileItem;
import com.talentserv.blink.dto.FigmaFilesRequest;
import com.talentserv.blink.dto.FigmaFrameItem;
import com.talentserv.blink.dto.FigmaFramesRequest;
import com.talentserv.blink.dto.FigmaIngestRequest;
import com.talentserv.blink.dto.FigmaJiraRef;
import com.talentserv.blink.dto.FigmaJiraUpdate;
import com.talentserv.blink.dto.FigmaScreenBinding;
import com.talentserv.blink.dto.FigmaStoryRef;
import com.talentserv.blink.dto.FigmaWebhookResult;
import com.talentserv.blink.dto.JiraCommentCreateRequest;
import com.talentserv.blink.dto.JiraCommentCreateResponse;
import com.talentserv.blink.dto.StoredFigmaDesign;
import com.talentserv.blink.dto.StoredIntegration;
import com.talentserv.blink.error.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class FigmaDesignService {

    private static final Logger log = LoggerFactory.getLogger(FigmaDesignService.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern FILE_KEY = Pattern.compile("(?:file|design|proto)/([A-Za-z0-9]{10,})");
    private static final String DESIGN_MARKER = "blink-design-sync";
    private static final int FIGMA_FILE_COOLDOWN_SECONDS = 600;

    private final IntegrationHttpGateway http;
    private final ProjectIntegrationStore integrations;
    private final FigmaDesignStore designs;
    private final IntegrationConnectService jira;
    private final BlinkProperties properties;
    private final ConcurrentHashMap<String, Instant> figmaReadCooldownUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> figmaReadStrikes = new ConcurrentHashMap<>();

    public FigmaDesignService(
            IntegrationHttpGateway http,
            ProjectIntegrationStore integrations,
            FigmaDesignStore designs,
            IntegrationConnectService jira,
            BlinkProperties properties
    ) {
        this.http = http;
        this.integrations = integrations;
        this.designs = designs;
        this.jira = jira;
        this.properties = properties;
    }

    public List<FigmaFileItem> listFiles(FigmaFilesRequest request) {
        if (request == null) {
            return List.of();
        }
        Long projectId = parseProjectId(request.projectId());
        StoredIntegration stored = loadFigma(projectId);
        String token = firstNonBlank(request.token(), stored == null ? null : stored.accessToken());
        String figmaProjectId = firstNonBlank(request.figmaProjectId(), stored == null ? null : stored.projectKey());
        if (token == null || figmaProjectId == null) {
            return List.of();
        }
        return fetchFiles(token, figmaProjectId);
    }

    public List<FigmaFrameItem> listFrames(FigmaFramesRequest request) {
        if (request == null) {
            return List.of();
        }
        Long projectId = parseProjectId(request.projectId());
        StoredIntegration stored = loadFigma(projectId);
        String token = firstNonBlank(request.token(), stored == null ? null : stored.accessToken());
        String fileKey = parseFileKey(firstNonBlank(request.fileKey(), request.fileUrl()));
        if (token == null || fileKey == null) {
            return List.of();
        }
        FileSnapshot snapshot = fetchSnapshot(token, fileKey);
        List<FigmaFrameItem> frames = new ArrayList<>();
        for (FigmaScreenBinding screen : snapshot.screens) {
            frames.add(new FigmaFrameItem(screen.nodeId(), screen.name(), screen.pageId(), screen.pageName(), screen.type()));
        }
        return frames;
    }

    public FigmaDesignBindingResponse getBinding(String projectIdRaw) {
        Long projectId = parseProjectId(projectIdRaw);
        if (projectId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Project id is required.");
        }
        List<StoredFigmaDesign> rows = designs.findByProjectId(projectId);
        if (rows.isEmpty()) {
            return emptyResponse(projectIdRaw, null);
        }
        return toResponse(rows.get(0), List.of(), List.of(), markdown(rows.get(0)));
    }

    public FigmaDesignBindingResponse saveBinding(FigmaDesignBindingRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Figma design binding is required.");
        }
        Long projectId = parseProjectId(request.projectId());
        if (projectId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Project id is required.");
        }
        StoredIntegration figma = requireFigma(projectId);
        String fileKey = parseFileKey(firstNonBlank(request.fileKey(), request.fileUrl()));
        if (fileKey == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Select a Figma file or paste a file URL.");
        }
        boolean syncJira = request.syncJira() == null || request.syncJira();
        StoredFigmaDesign existing = designs.find(projectId, fileKey).orElse(null);
        List<FigmaScreenBinding> screens = request.screens() == null ? List.of() : request.screens();
        if (screens.isEmpty() && existing != null) {
            screens = readScreens(existing.snapshotJson());
        }
        screens = applyMatches(screens, request.stories(), request.jiraIssues());
        String snapshotJson;
        if (screens.isEmpty() && existing != null && existing.snapshotJson() != null && !existing.snapshotJson().isBlank()) {
            snapshotJson = existing.snapshotJson();
        } else {
            snapshotJson = writeScreens(screens);
        }
        StoredFigmaDesign saved = new StoredFigmaDesign(
                projectId,
                fileKey,
                firstNonBlank(request.fileName(), existing == null ? null : existing.fileName(), fileKey),
                firstNonBlank(request.fileUrl(), existing == null ? null : existing.fileUrl(), fileUrl(fileKey)),
                firstNonBlank(request.figmaProjectId(), figma.projectKey(), existing == null ? null : existing.figmaProjectId()),
                firstNonBlank(figma.organization(), existing == null ? null : existing.teamId()),
                syncJira,
                existing == null ? null : existing.webhookId(),
                existing == null ? null : existing.webhookPasscode(),
                existing == null ? null : existing.fileVersion(),
                existing == null ? null : existing.lastSyncedAt(),
                existing == null ? null : existing.lastSyncSummary(),
                existing == null ? null : existing.webhookStatus(),
                snapshotJson
        );
        saved = ensureWebhook(saved, figma.accessToken(), request.webhookPublicBase());
        designs.upsert(saved);
        return toResponse(saved, List.of(), List.of(), markdown(saved));
    }

    public FigmaDesignBindingResponse clearBinding(String projectIdRaw) {
        Long projectId = parseProjectId(projectIdRaw);
        if (projectId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Project id is required.");
        }
        designs.deleteByProjectId(projectId);
        return emptyResponse(projectIdRaw, null);
    }

    public FigmaDesignBindingResponse ingest(FigmaIngestRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Figma ingest request is required.");
        }
        Long projectId = parseProjectId(request.projectId());
        if (projectId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Project id is required.");
        }
        StoredIntegration figma = requireFigma(projectId);
        StoredFigmaDesign existing = resolveExisting(projectId, request.fileKey(), request.fileUrl());
        String fileKey = parseFileKey(firstNonBlank(request.fileKey(), request.fileUrl(), existing == null ? null : existing.fileKey()));
        if (fileKey == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Select a Figma file first.");
        }
        boolean syncJira = request.syncJira() == null
                ? (existing == null || existing.syncJira())
                : request.syncJira();
        return syncFile(
                projectId,
                fileKey,
                figma,
                existing,
                syncJira,
                request.stories(),
                request.jiraIssues(),
                request.webhookPublicBase(),
                true
        );
    }

    public FigmaWebhookResult handleWebhook(String body) {
        JsonNode root = readJson(body);
        String eventType = firstNonBlank(
                text(root, "event_type"),
                text(root, "eventType"),
                "FILE_UPDATE"
        );
        String fileKey = parseFileKey(firstNonBlank(text(root, "file_key"), text(root, "fileKey")));
        String passcode = firstNonBlank(text(root, "passcode"), text(root, "webhook_passcode"));
        if (passcode == null || passcode.isBlank()) {
            return new FigmaWebhookResult("ignored", eventType, fileKey, "Missing webhook passcode.", null);
        }
        List<StoredFigmaDesign> matches = designs.findByPasscode(passcode);
        if (matches.isEmpty()) {
            log.warn("Figma webhook passcode did not match a binding event={}", eventType);
            return new FigmaWebhookResult("ignored", eventType, fileKey, "Unknown webhook passcode.", null);
        }
        if ("PING".equalsIgnoreCase(eventType) || "FILE_DELETE".equalsIgnoreCase(eventType)) {
            return new FigmaWebhookResult("ok", eventType, fileKey, "Webhook " + eventType + " acknowledged.", null);
        }
        FigmaDesignBindingResponse last = null;
        int updated = 0;
        for (StoredFigmaDesign bound : matches) {
            if (fileKey != null && !fileKey.equals(bound.fileKey())) {
                continue;
            }
            StoredIntegration figma = loadFigma(bound.projectId());
            if (figma == null || figma.accessToken() == null || figma.accessToken().isBlank()) {
                continue;
            }
            try {
                last = syncFile(
                        bound.projectId(),
                        bound.fileKey(),
                        figma,
                        bound,
                        bound.syncJira(),
                        List.of(),
                        List.of(),
                        null,
                        false
                );
                updated += 1;
            } catch (ApiException ex) {
                if (ex.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                    log.warn("Figma webhook skipped; file reads are rate-limited key={}", bound.fileKey());
                    continue;
                }
                throw ex;
            }
        }
        if (updated == 0) {
            return new FigmaWebhookResult("ok", eventType, fileKey, "No matching file binding for this event.", last);
        }
        return new FigmaWebhookResult("ok", eventType, fileKey, "Synced " + updated + " Blink project(s).", last);
    }

    private FigmaDesignBindingResponse syncFile(
            Long projectId,
            String fileKey,
            StoredIntegration figma,
            StoredFigmaDesign existing,
            boolean syncJira,
            List<FigmaStoryRef> stories,
            List<FigmaJiraRef> jiraIssues,
            String webhookPublicBase,
            boolean force
    ) {
        if (existing != null && figmaReadCooling(fileKey)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, figmaPausedMessage(fileKey));
        }
        FileSnapshot snapshot = fetchSnapshot(figma.accessToken(), fileKey);
        List<FigmaScreenBinding> previous = existing == null ? List.of() : readScreens(existing.snapshotJson());
        Map<String, FigmaScreenBinding> previousById = index(previous);
        List<FigmaScreenBinding> merged = new ArrayList<>();
        for (FigmaScreenBinding screen : snapshot.screens) {
            FigmaScreenBinding prior = previousById.get(screen.nodeId());
            if (prior != null) {
                merged.add(screen
                        .withBinding(prior.storyId(), prior.jiraKey())
                        .withFingerprint(screen.fingerprint())
                        .withThumbnail(firstNonBlank(screen.thumbnailUrl(), prior.thumbnailUrl())));
            } else {
                merged.add(screen);
            }
        }
        merged = applyMatches(merged, stories, jiraIssues);
        merged = withThumbnails(figma.accessToken(), fileKey, merged);
        List<FigmaDesignChange> changes = diff(previous, merged);
        if (changes.isEmpty() && fileVersionChanged(existing, snapshot.version)) {
            changes.addAll(markScreensUpdated(merged, "changed in Figma"));
        }
        if (changes.isEmpty() && missedTicketLink(existing, merged)) {
            changes.addAll(markScreensUpdated(merged, "linked from Figma"));
        }
        boolean versionUnchanged = existing != null
                && existing.fileVersion() != null
                && existing.fileVersion().equals(snapshot.version)
                && !force;
        StoredFigmaDesign saved = new StoredFigmaDesign(
                projectId,
                fileKey,
                firstNonBlank(snapshot.name, existing == null ? null : existing.fileName(), fileKey),
                firstNonBlank(existing == null ? null : existing.fileUrl(), fileUrl(fileKey)),
                firstNonBlank(existing == null ? null : existing.figmaProjectId(), figma.projectKey()),
                firstNonBlank(figma.organization(), existing == null ? null : existing.teamId()),
                syncJira,
                existing == null ? null : existing.webhookId(),
                existing == null ? null : existing.webhookPasscode(),
                snapshot.version,
                Instant.now(),
                summary(changes, versionUnchanged),
                existing == null ? null : existing.webhookStatus(),
                writeScreens(merged)
        );
        saved = ensureWebhook(saved, figma.accessToken(), webhookPublicBase);
        List<FigmaJiraUpdate> jiraUpdates = List.of();
        if (!versionUnchanged && syncJira && !changes.isEmpty()) {
            jiraUpdates = pushJiraUpdates(projectId, saved, changes);
        }
        if (versionUnchanged) {
            saved = saved.withSync(
                    snapshot.version,
                    Instant.now(),
                    "Figma version unchanged; Jira was not updated.",
                    saved.snapshotJson()
            );
            changes = List.of();
            jiraUpdates = List.of();
        }
        designs.upsert(saved);
        return toResponse(saved, changes, jiraUpdates, markdown(saved));
    }

    private List<FigmaJiraUpdate> pushJiraUpdates(Long projectId, StoredFigmaDesign design, List<FigmaDesignChange> changes) {
        Map<String, List<FigmaDesignChange>> byIssue = new LinkedHashMap<>();
        List<FigmaDesignChange> unbound = new ArrayList<>();
        for (FigmaDesignChange change : changes) {
            if (change.jiraKey() != null && !change.jiraKey().isBlank()) {
                byIssue.computeIfAbsent(change.jiraKey(), ignored -> new ArrayList<>()).add(change);
            } else {
                unbound.add(change);
            }
        }
        List<FigmaJiraUpdate> updates = new ArrayList<>();
        for (Map.Entry<String, List<FigmaDesignChange>> entry : byIssue.entrySet()) {
            updates.add(commentIssue(projectId, entry.getKey(), design, entry.getValue()));
        }
        if (!unbound.isEmpty()) {
            String epicKey = firstBoundEpic(byIssue.keySet());
            if (epicKey == null) {
                for (FigmaScreenBinding screen : readScreens(design.snapshotJson())) {
                    if (screen.jiraKey() != null && !screen.jiraKey().isBlank()) {
                        epicKey = screen.jiraKey();
                        break;
                    }
                }
            }
            if (epicKey != null) {
                updates.add(commentIssue(projectId, epicKey, design, unbound));
            }
        }
        return updates;
    }

    private FigmaJiraUpdate commentIssue(
            Long projectId,
            String issueKey,
            StoredFigmaDesign design,
            List<FigmaDesignChange> changes
    ) {
        String body = jiraComment(design, changes);
        try {
            JiraCommentCreateResponse posted = jira.createJiraComment(new JiraCommentCreateRequest(
                    String.valueOf(projectId),
                    null,
                    null,
                    null,
                    null,
                    null,
                    issueKey,
                    body,
                    DESIGN_MARKER
            ));
            return new FigmaJiraUpdate(issueKey, "updated", posted.commentId(), "Posted design change on " + issueKey);
        } catch (Exception ex) {
            log.warn("Could not post Figma design sync to {}: {}", issueKey, ex.toString());
            return new FigmaJiraUpdate(issueKey, "failed", null, ex.getMessage());
        }
    }

    private StoredFigmaDesign ensureWebhook(StoredFigmaDesign design, String token, String webhookPublicBase) {
        if (design == null || !design.syncJira()) {
            return design;
        }
        if (design.webhookId() != null && !design.webhookId().isBlank()) {
            return design;
        }
        String endpoint = webhookEndpoint(webhookPublicBase);
        if (endpoint == null || isLoopback(endpoint)) {
            return design.withWebhook(design.webhookId(), design.webhookPasscode(), "manual-sync (Figma cannot reach localhost)");
        }
        String passcode = firstNonBlank(design.webhookPasscode(), UUID.randomUUID().toString().replace("-", ""));
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("event_type", "FILE_UPDATE");
        payload.put("endpoint", endpoint);
        payload.put("passcode", passcode);
        payload.put("description", "Blink design-to-Jira sync");
        payload.put("status", "ACTIVE");
        if (design.fileKey() != null) {
            payload.put("context", "file");
            payload.put("context_id", design.fileKey());
        }
        if (design.teamId() != null && !design.teamId().isBlank()) {
            payload.put("team_id", design.teamId());
        }
        try {
            IntegrationHttpGateway.IntegrationHttpResponse res = http.post(
                    "https://api.figma.com/v2/webhooks",
                    figmaHeaders(token),
                    MAPPER.writeValueAsString(payload)
            );
            if (res.status() >= 200 && res.status() < 300) {
                String webhookId = firstNonBlank(text(readJson(res.body()), "id"), text(readJson(res.body()), "webhook_id"));
                return design.withWebhook(webhookId, passcode, "active");
            }
            log.warn("Figma webhook register failed status={} body={}", res.status(), abbreviate(res.body(), 240));
            return design.withWebhook(null, passcode, "pending (HTTP " + res.status() + ")");
        } catch (Exception ex) {
            log.warn("Figma webhook register failed: {}", ex.toString());
            return design.withWebhook(null, passcode, "pending (" + ex.getMessage() + ")");
        }
    }

    private List<FigmaFileItem> fetchFiles(String token, String figmaProjectId) {
        List<FigmaFileItem> files = new ArrayList<>();
        try {
            IntegrationHttpGateway.IntegrationHttpResponse res = http.get(
                    "https://api.figma.com/v1/projects/" + encode(figmaProjectId) + "/files",
                    figmaHeaders(token)
            );
            if (res.status() != 200 || res.body() == null) {
                return files;
            }
            JsonNode list = readJson(res.body()).path("files");
            if (!list.isArray()) {
                return files;
            }
            for (JsonNode item : list) {
                String key = item.path("key").asText("");
                if (key.isBlank()) {
                    continue;
                }
                files.add(new FigmaFileItem(
                        key,
                        firstNonBlank(item.path("name").asText(""), key),
                        emptyToNull(item.path("thumbnail_url").asText("")),
                        emptyToNull(item.path("last_modified").asText(""))
                ));
            }
        } catch (Exception ex) {
            log.warn("Could not list Figma files: {}", ex.toString());
        }
        return files;
    }

    private List<FigmaScreenBinding> withThumbnails(String token, String fileKey, List<FigmaScreenBinding> screens) {
        if (screens == null || screens.isEmpty() || token == null || token.isBlank() || fileKey == null || fileKey.isBlank()) {
            return screens == null ? List.of() : screens;
        }
        if (figmaReadCooling(fileKey)) {
            return screens;
        }
        boolean needsThumb = screens.stream().anyMatch(screen -> screen.thumbnailUrl() == null || screen.thumbnailUrl().isBlank());
        if (!needsThumb) {
            return screens;
        }
        int limit = Math.min(screens.size(), 12);
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            FigmaScreenBinding screen = screens.get(i);
            String nodeId = screen.nodeId();
            if (nodeId == null || nodeId.isBlank()) {
                continue;
            }
            if (screen.thumbnailUrl() != null && !screen.thumbnailUrl().isBlank()) {
                continue;
            }
            if (!ids.isEmpty()) {
                ids.append(',');
            }
            ids.append(encode(nodeId));
        }
        if (ids.isEmpty()) {
            return screens;
        }
        try {
            IntegrationHttpGateway.IntegrationHttpResponse res = http.get(
                    "https://api.figma.com/v1/images/" + encode(fileKey) + "?ids=" + ids + "&format=png&scale=1",
                    figmaHeaders(token)
            );
            if (res.status() == 429) {
                markFigmaReadCooldown(fileKey, res);
                log.warn("Figma preview images rate-limited key={} — keeping last thumbnails", fileKey);
                return screens;
            }
            if (res.status() != 200 || res.body() == null || res.body().isBlank()) {
                return screens;
            }
            JsonNode images = readJson(res.body()).path("images");
            List<FigmaScreenBinding> out = new ArrayList<>(screens.size());
            for (FigmaScreenBinding screen : screens) {
                String url = emptyToNull(images.path(screen.nodeId()).asText(""));
                out.add(url == null ? screen : screen.withThumbnail(url));
            }
            return out;
        } catch (Exception ex) {
            log.warn("Could not fetch Figma screen previews: {}", ex.toString());
            return screens;
        }
    }

    private FileSnapshot fetchSnapshot(String token, String fileKey) {
        if (figmaReadCooling(fileKey)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, figmaPausedMessage(fileKey));
        }
        String url = "https://api.figma.com/v1/files/" + encode(fileKey) + "?depth=2";
        IntegrationHttpGateway.IntegrationHttpResponse res = http.get(url, figmaHeaders(token));
        if (res.status() >= 500) {
            try {
                Thread.sleep(800);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            res = http.get(url, figmaHeaders(token));
        }
        if (res.status() == 429) {
            markFigmaReadCooldown(fileKey, res);
            log.warn("Figma file read rate-limited key={} body={}", fileKey, abbreviate(res.body(), 240));
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, figmaPausedMessage(fileKey));
        }
        if (res.status() < 200 || res.status() >= 300 || res.body() == null || res.body().isBlank()) {
            log.warn("Figma file read failed key={} status={} body={}", fileKey, res.status(), abbreviate(res.body(), 240));
            throw new ApiException(HttpStatus.BAD_GATEWAY, figmaFileError(res.status(), res.body()));
        }
        JsonNode root = readJson(res.body());
        String name = firstNonBlank(root.path("name").asText(""), fileKey);
        String version = firstNonBlank(root.path("version").asText(""), root.path("lastModified").asText(""));
        List<FigmaScreenBinding> screens = new ArrayList<>();
        JsonNode document = root.path("document");
        JsonNode pages = document.path("children");
        if (pages.isArray()) {
            for (JsonNode page : pages) {
                String pageId = page.path("id").asText("");
                String pageName = firstNonBlank(page.path("name").asText(""), "Page");
                JsonNode children = page.path("children");
                if (!children.isArray()) {
                    continue;
                }
                for (JsonNode child : children) {
                    String type = child.path("type").asText("");
                    if (!isScreenType(type)) {
                        continue;
                    }
                    String nodeId = child.path("id").asText("");
                    if (nodeId.isBlank()) {
                        continue;
                    }
                    String screenName = firstNonBlank(child.path("name").asText(""), nodeId);
                    screens.add(new FigmaScreenBinding(
                            nodeId,
                            screenName,
                            pageId,
                            pageName,
                            type,
                            null,
                            null,
                            fingerprint(child),
                            null
                    ));
                }
            }
        }
        clearFigmaReadLimit(fileKey);
        return new FileSnapshot(name, version, screens);
    }

    static List<FigmaDesignChange> diff(List<FigmaScreenBinding> previous, List<FigmaScreenBinding> current) {
        Map<String, FigmaScreenBinding> before = index(previous);
        Map<String, FigmaScreenBinding> after = index(current);
        List<FigmaDesignChange> changes = new ArrayList<>();
        for (FigmaScreenBinding screen : current) {
            FigmaScreenBinding prior = before.get(screen.nodeId());
            if (prior == null) {
                changes.add(new FigmaDesignChange(
                        "added",
                        screen.nodeId(),
                        screen.name(),
                        null,
                        screen.storyId(),
                        screen.jiraKey(),
                        "New screen \"" + screen.name() + "\""
                ));
                continue;
            }
            boolean renamed = prior.name() != null && screen.name() != null && !prior.name().equals(screen.name());
            boolean contentChanged = prior.fingerprint() != null
                    && screen.fingerprint() != null
                    && !prior.fingerprint().equals(screen.fingerprint());
            if (renamed || contentChanged) {
                changes.add(new FigmaDesignChange(
                        renamed && contentChanged ? "updated" : renamed ? "renamed" : "updated",
                        screen.nodeId(),
                        screen.name(),
                        prior.name(),
                        screen.storyId(),
                        screen.jiraKey(),
                        renamed
                                ? "Screen renamed from \"" + prior.name() + "\" to \"" + screen.name() + "\""
                                : "Screen \"" + screen.name() + "\" changed"
                ));
            }
        }
        for (FigmaScreenBinding prior : previous) {
            if (!after.containsKey(prior.nodeId())) {
                changes.add(new FigmaDesignChange(
                        "removed",
                        prior.nodeId(),
                        prior.name(),
                        prior.name(),
                        prior.storyId(),
                        prior.jiraKey(),
                        "Screen \"" + prior.name() + "\" was removed"
                ));
            }
        }
        return changes;
    }

    static List<FigmaScreenBinding> applyMatches(
            List<FigmaScreenBinding> screens,
            List<FigmaStoryRef> stories,
            List<FigmaJiraRef> jiraIssues
    ) {
        if (screens == null || screens.isEmpty()) {
            return screens == null ? List.of() : screens;
        }
        Map<String, String> jiraBySource = new LinkedHashMap<>();
        if (jiraIssues != null) {
            for (FigmaJiraRef ref : jiraIssues) {
                if (ref != null && ref.sourceId() != null && ref.jiraKey() != null && !ref.jiraKey().isBlank()) {
                    jiraBySource.put(ref.sourceId(), ref.jiraKey());
                }
            }
        }
        Map<String, FigmaStoryRef> storiesByNorm = new LinkedHashMap<>();
        if (stories != null) {
            for (FigmaStoryRef story : stories) {
                if (story == null || story.id() == null) {
                    continue;
                }
                String norm = normalizeName(story.title());
                if (!norm.isBlank()) {
                    storiesByNorm.putIfAbsent(norm, story);
                }
            }
        }
        List<FigmaScreenBinding> out = new ArrayList<>();
        java.util.LinkedHashSet<String> usedStories = new java.util.LinkedHashSet<>();
        for (FigmaScreenBinding screen : screens) {
            if (screen.storyId() != null && !screen.storyId().isBlank()) {
                usedStories.add(screen.storyId());
            }
        }
        for (FigmaScreenBinding screen : screens) {
            String storyId = screen.storyId();
            String jiraKey = screen.jiraKey();
            if ((storyId == null || storyId.isBlank()) && !storiesByNorm.isEmpty()) {
                FigmaStoryRef matched = matchStory(screen.name(), storiesByNorm, usedStories);
                if (matched != null) {
                    storyId = matched.id();
                    usedStories.add(storyId);
                }
            }
            if ((jiraKey == null || jiraKey.isBlank()) && storyId != null) {
                jiraKey = jiraBySource.get(storyId);
            }
            out.add(screen.withBinding(emptyToNull(storyId), emptyToNull(jiraKey)));
        }
        if (stories != null && !stories.isEmpty()) {
            java.util.ArrayDeque<FigmaStoryRef> leftoverStories = new java.util.ArrayDeque<>();
            for (FigmaStoryRef story : stories) {
                if (story != null && story.id() != null && !usedStories.contains(story.id())) {
                    leftoverStories.add(story);
                }
            }
            List<FigmaScreenBinding> paired = new ArrayList<>(out.size());
            for (FigmaScreenBinding screen : out) {
                if ((screen.storyId() == null || screen.storyId().isBlank()) && !leftoverStories.isEmpty()) {
                    FigmaStoryRef story = leftoverStories.removeFirst();
                    usedStories.add(story.id());
                    String jiraKey = firstNonBlank(screen.jiraKey(), jiraBySource.get(story.id()));
                    paired.add(screen.withBinding(story.id(), emptyToNull(jiraKey)));
                } else {
                    paired.add(screen);
                }
            }
            return paired;
        }
        return out;
    }

    private static FigmaStoryRef matchStory(
            String screenName,
            Map<String, FigmaStoryRef> storiesByNorm,
            java.util.Set<String> usedStories
    ) {
        String screenNorm = normalizeName(screenName);
        if (screenNorm.isBlank()) {
            return null;
        }
        FigmaStoryRef exact = storiesByNorm.get(screenNorm);
        if (exact != null && (usedStories == null || !usedStories.contains(exact.id()))) {
            return exact;
        }
        FigmaStoryRef best = null;
        int bestLen = 0;
        for (Map.Entry<String, FigmaStoryRef> entry : storiesByNorm.entrySet()) {
            FigmaStoryRef story = entry.getValue();
            if (story == null || story.id() == null || (usedStories != null && usedStories.contains(story.id()))) {
                continue;
            }
            String storyNorm = entry.getKey();
            if (storyNorm.length() < 4 || screenNorm.length() < 4) {
                continue;
            }
            if (screenNorm.contains(storyNorm) || storyNorm.contains(screenNorm)) {
                int len = Math.min(storyNorm.length(), screenNorm.length());
                if (len > bestLen) {
                    best = story;
                    bestLen = len;
                }
            }
        }
        return best;
    }

    static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String fingerprint(JsonNode node) {
        StringBuilder seed = new StringBuilder();
        seed.append(node.path("name").asText("")).append('|');
        seed.append(node.path("type").asText("")).append('|');
        JsonNode children = node.path("children");
        if (children.isArray()) {
            seed.append(children.size()).append('|');
            for (JsonNode child : children) {
                seed.append(child.path("name").asText("")).append(':').append(child.path("type").asText("")).append(',');
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(seed.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 16);
        } catch (Exception ex) {
            return Integer.toHexString(seed.toString().hashCode());
        }
    }

    private String markdown(StoredFigmaDesign design) {
        List<FigmaScreenBinding> screens = readScreens(design.snapshotJson());
        StringBuilder out = new StringBuilder();
        out.append("# Design spec — ").append(firstNonBlank(design.fileName(), design.fileKey())).append("\n\n");
        out.append("- File: ").append(firstNonBlank(design.fileUrl(), fileUrl(design.fileKey()))).append('\n');
        out.append("- Version: ").append(firstNonBlank(design.fileVersion(), "unknown")).append('\n');
        if (design.lastSyncedAt() != null) {
            out.append("- Last synced: ").append(design.lastSyncedAt()).append('\n');
        }
        out.append("- Jira auto-sync: ").append(design.syncJira() ? "on" : "off").append("\n\n");
        out.append("## Screens\n\n");
        if (screens.isEmpty()) {
            out.append("_No top-level frames yet._\n");
            return out.toString();
        }
        for (FigmaScreenBinding screen : screens) {
            out.append("### ").append(screen.name()).append('\n');
            out.append("- Node: `").append(screen.nodeId()).append("`\n");
            if (screen.pageName() != null) {
                out.append("- Page: ").append(screen.pageName()).append('\n');
            }
            if (screen.storyId() != null) {
                out.append("- Story: ").append(screen.storyId()).append('\n');
            }
            if (screen.jiraKey() != null) {
                out.append("- Jira: ").append(screen.jiraKey()).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private String jiraComment(StoredFigmaDesign design, List<FigmaDesignChange> changes) {
        StringBuilder out = new StringBuilder();
        out.append("[").append(DESIGN_MARKER).append("]\n");
        out.append("Figma design update for ").append(firstNonBlank(design.fileName(), design.fileKey())).append('\n');
        out.append(firstNonBlank(design.fileUrl(), fileUrl(design.fileKey()))).append('\n');
        for (FigmaDesignChange change : changes) {
            out.append("- ").append(change.detail());
            if (change.nodeId() != null) {
                out.append(" (").append(change.nodeId()).append(')');
            }
            out.append('\n');
        }
        out.append("Blink will keep this ticket in sync when the bound frame changes.");
        return out.toString();
    }

    private FigmaDesignBindingResponse toResponse(
            StoredFigmaDesign design,
            List<FigmaDesignChange> changes,
            List<FigmaJiraUpdate> jiraUpdates,
            String markdown
    ) {
        return new FigmaDesignBindingResponse(
                true,
                String.valueOf(design.projectId()),
                design.fileKey(),
                design.fileName(),
                design.fileUrl(),
                design.fileVersion(),
                design.syncJira(),
                design.webhookId(),
                design.webhookStatus(),
                design.lastSyncedAt() == null ? null : design.lastSyncedAt().toString(),
                design.lastSyncSummary(),
                markdown,
                readScreens(design.snapshotJson()),
                changes,
                jiraUpdates
        );
    }

    private FigmaDesignBindingResponse emptyResponse(String projectId, String fileKey) {
        return new FigmaDesignBindingResponse(
                false,
                projectId,
                fileKey,
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private StoredFigmaDesign resolveExisting(Long projectId, String fileKey, String fileUrl) {
        String parsed = parseFileKey(firstNonBlank(fileKey, fileUrl));
        if (parsed != null) {
            Optional<StoredFigmaDesign> found = designs.find(projectId, parsed);
            if (found.isPresent()) {
                return found.get();
            }
        }
        List<StoredFigmaDesign> all = designs.findByProjectId(projectId);
        return all.isEmpty() ? null : all.get(0);
    }

    private StoredIntegration requireFigma(Long projectId) {
        StoredIntegration stored = loadFigma(projectId);
        if (stored == null || stored.accessToken() == null || stored.accessToken().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Connect Figma before ingesting a design.");
        }
        return stored;
    }

    private StoredIntegration loadFigma(Long projectId) {
        if (projectId == null) {
            return null;
        }
        return integrations.find(projectId, "figma")
                .filter(row -> row.accessToken() != null && !row.accessToken().isBlank())
                .or(() -> integrations.findLatest("figma"))
                .orElse(null);
    }

    private String webhookEndpoint(String requestedBase) {
        String configured = properties == null ? null : properties.getFigmaWebhookPublicBase();
        String base = firstNonBlank(requestedBase, configured);
        if (base == null || base.isBlank()) {
            return null;
        }
        String root = base.trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (root.endsWith("/api")) {
            root = root.substring(0, root.length() - 4);
        }
        return root + "/api/integrations/figma/webhooks";
    }

    static String parseFileKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        Matcher matcher = FILE_KEY.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (trimmed.matches("[A-Za-z0-9]{10,}")) {
            return trimmed;
        }
        return null;
    }

    private List<FigmaScreenBinding> readScreens(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(snapshotJson);
            JsonNode list = root.isArray() ? root : root.path("screens");
            if (!list.isArray()) {
                return List.of();
            }
            List<FigmaScreenBinding> screens = new ArrayList<>();
            for (JsonNode item : list) {
                String nodeId = item.path("nodeId").asText("");
                if (nodeId.isBlank()) {
                    continue;
                }
                screens.add(new FigmaScreenBinding(
                        nodeId,
                        emptyToNull(item.path("name").asText("")),
                        emptyToNull(item.path("pageId").asText("")),
                        emptyToNull(item.path("pageName").asText("")),
                        emptyToNull(item.path("type").asText("")),
                        emptyToNull(item.path("storyId").asText("")),
                        emptyToNull(item.path("jiraKey").asText("")),
                        emptyToNull(item.path("fingerprint").asText("")),
                        emptyToNull(item.path("thumbnailUrl").asText(""))
                ));
            }
            return screens;
        } catch (Exception ex) {
            log.warn("Could not parse Figma snapshot: {}", ex.toString());
            return List.of();
        }
    }

    private String writeScreens(List<FigmaScreenBinding> screens) {
        ArrayNode list = MAPPER.createArrayNode();
        for (FigmaScreenBinding screen : screens) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("nodeId", screen.nodeId());
            put(item, "name", screen.name());
            put(item, "pageId", screen.pageId());
            put(item, "pageName", screen.pageName());
            put(item, "type", screen.type());
            put(item, "storyId", screen.storyId());
            put(item, "jiraKey", screen.jiraKey());
            put(item, "fingerprint", screen.fingerprint());
            put(item, "thumbnailUrl", screen.thumbnailUrl());
            list.add(item);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.set("screens", list);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception ex) {
            return "{\"screens\":[]}";
        }
    }

    private static Map<String, FigmaScreenBinding> index(List<FigmaScreenBinding> screens) {
        Map<String, FigmaScreenBinding> map = new LinkedHashMap<>();
        if (screens == null) {
            return map;
        }
        for (FigmaScreenBinding screen : screens) {
            if (screen != null && screen.nodeId() != null) {
                map.put(screen.nodeId(), screen);
            }
        }
        return map;
    }

    private static boolean isScreenType(String type) {
        return "FRAME".equals(type) || "COMPONENT".equals(type) || "COMPONENT_SET".equals(type) || "SECTION".equals(type);
    }

    private static boolean fileVersionChanged(StoredFigmaDesign existing, String version) {
        return existing != null
                && existing.fileVersion() != null
                && !existing.fileVersion().isBlank()
                && version != null
                && !version.isBlank()
                && !existing.fileVersion().equals(version);
    }

    private static boolean missedTicketLink(StoredFigmaDesign existing, List<FigmaScreenBinding> screens) {
        if (existing == null || existing.lastSyncSummary() == null) {
            return false;
        }
        if (!existing.lastSyncSummary().startsWith("Design snapshot stored. No screen changes.")) {
            return false;
        }
        if (screens == null) {
            return false;
        }
        for (FigmaScreenBinding screen : screens) {
            if (screen.jiraKey() != null && !screen.jiraKey().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static List<FigmaDesignChange> markScreensUpdated(List<FigmaScreenBinding> screens, String reason) {
        List<FigmaDesignChange> changes = new ArrayList<>();
        if (screens == null) {
            return changes;
        }
        for (FigmaScreenBinding screen : screens) {
            if (screen.jiraKey() == null || screen.jiraKey().isBlank()) {
                continue;
            }
            changes.add(new FigmaDesignChange(
                    "updated",
                    screen.nodeId(),
                    screen.name(),
                    screen.name(),
                    screen.storyId(),
                    screen.jiraKey(),
                    "Screen \"" + screen.name() + "\" " + reason
            ));
        }
        return changes;
    }

    private static String summary(List<FigmaDesignChange> changes, boolean versionUnchanged) {
        if (versionUnchanged) {
            return "Figma version unchanged; Jira was not updated.";
        }
        if (changes == null || changes.isEmpty()) {
            return "Design snapshot stored. No screen changes.";
        }
        return changes.size() + " screen change" + (changes.size() == 1 ? "" : "s") + " detected.";
    }

    private String figmaPausedMessage(String fileKey) {
        return "Figma has used up file reads for this file. A Starter file only allows a few reads per month. Duplicate it into a Professional team where you have a Full or Dev seat, then sync once.";
    }

    private static String figmaFileError(int status, String body) {
        if (status == 429) {
            return "Figma has used up file reads for this file. A Starter file only allows a few reads per month. Duplicate it into a Professional team where you have a Full or Dev seat, then sync once.";
        }
        if (status == 401 || status == 403) {
            return "Figma rejected this token. Reconnect Figma on Integrations, then sync again.";
        }
        if (status == 404) {
            return "That Figma file was not found. Bind the file again.";
        }
        String err = "";
        try {
            if (body != null && !body.isBlank() && body.trim().startsWith("{")) {
                JsonNode node = MAPPER.readTree(body);
                err = firstNonBlank(node.path("err").asText(""), node.path("message").asText(""), node.path("error").asText(""));
            }
        } catch (Exception ignored) {
        }
        if (err != null && !err.isBlank()) {
            return "Could not refresh the Figma file. " + err;
        }
        return "Could not refresh the Figma file. Try Sync from Figma again.";
    }

    private void markFigmaReadCooldown(String fileKey, IntegrationHttpGateway.IntegrationHttpResponse res) {
        if (fileKey == null || fileKey.isBlank()) {
            return;
        }
        int strikes = figmaReadStrikes.merge(fileKey, 1, Integer::sum);
        int fallback = strikes <= 1 ? FIGMA_FILE_COOLDOWN_SECONDS : strikes == 2 ? 1200 : 1800;
        int retryAfter = parseRetryAfterSeconds(res);
        int wait = Math.min(1800, Math.max(fallback, retryAfter));
        Instant until = Instant.now().plusSeconds(wait);
        figmaReadCooldownUntil.merge(fileKey, until, (existing, incoming) -> existing.isAfter(incoming) ? existing : incoming);
    }

    private void clearFigmaReadLimit(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            return;
        }
        figmaReadCooldownUntil.remove(fileKey);
        figmaReadStrikes.remove(fileKey);
    }

    private static int parseRetryAfterSeconds(IntegrationHttpGateway.IntegrationHttpResponse res) {
        if (res == null) {
            return 0;
        }
        String raw = res.header("Retry-After");
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private long figmaReadRemainingSeconds(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            return 0;
        }
        Instant until = figmaReadCooldownUntil.get(fileKey);
        if (until == null) {
            return 0;
        }
        long seconds = Duration.between(Instant.now(), until).getSeconds();
        return Math.max(0, seconds);
    }

    private boolean figmaReadCooling(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            return false;
        }
        Instant until = figmaReadCooldownUntil.get(fileKey);
        if (until == null) {
            return false;
        }
        if (!Instant.now().isBefore(until)) {
            figmaReadCooldownUntil.remove(fileKey);
            return false;
        }
        return true;
    }

    private static String firstBoundEpic(java.util.Set<String> keys) {
        return keys == null || keys.isEmpty() ? null : keys.iterator().next();
    }

    private Long parseProjectId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private JsonNode readJson(String body) {
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception ex) {
            return MAPPER.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private static void put(ObjectNode item, String field, String value) {
        if (value != null && !value.isBlank()) {
            item.put(field, value);
        }
    }

    private static Map<String, String> figmaHeaders(String token) {
        return FigmaAuth.headers(token);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String fileUrl(String fileKey) {
        return fileKey == null ? null : "https://www.figma.com/design/" + fileKey;
    }

    private static boolean isLoopback(String uri) {
        try {
            URI parsed = URI.create(uri);
            String host = parsed.getHost();
            if (host == null) {
                return true;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return "localhost".equals(lower) || "127.0.0.1".equals(lower);
        } catch (Exception ex) {
            return true;
        }
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private record FileSnapshot(String name, String version, List<FigmaScreenBinding> screens) {
    }
}
