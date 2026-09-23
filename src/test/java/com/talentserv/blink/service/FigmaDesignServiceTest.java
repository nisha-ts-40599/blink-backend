package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.dto.FigmaDesignBindingRequest;
import com.talentserv.blink.dto.FigmaDesignBindingResponse;
import com.talentserv.blink.dto.FigmaFileItem;
import com.talentserv.blink.dto.FigmaFilesRequest;
import com.talentserv.blink.dto.FigmaFrameItem;
import com.talentserv.blink.dto.FigmaFramesRequest;
import com.talentserv.blink.dto.FigmaIngestRequest;
import com.talentserv.blink.dto.FigmaJiraRef;
import com.talentserv.blink.dto.FigmaScreenBinding;
import com.talentserv.blink.dto.FigmaStoryRef;
import com.talentserv.blink.dto.FigmaWebhookResult;
import com.talentserv.blink.dto.StoredFigmaDesign;
import com.talentserv.blink.dto.StoredIntegration;
import com.talentserv.blink.error.ApiException;

class FigmaDesignServiceTest {

    private final Map<String, IntegrationHttpGateway.IntegrationHttpResponse> gets = new HashMap<>();
    private final Map<String, IntegrationHttpGateway.IntegrationHttpResponse> posts = new HashMap<>();
    private MemoryProjectIntegrationStore integrations;
    private InMemoryFigmaDesignStore designs;
    private FigmaDesignService service;

    @BeforeEach
    void setUp() {
        integrations = new MemoryProjectIntegrationStore();
        designs = new InMemoryFigmaDesignStore();
        integrations.upsert(new StoredIntegration(
                42L, "figma", "ada", "https://www.figma.com", null, null, "111", null,
                "222", "Mobile App", null, null, "token", "figd_token", null, null
        ));
        BlinkProperties props = new BlinkProperties();
        IntegrationHttpGateway http = new IntegrationHttpGateway() {
            @Override
            public IntegrationHttpResponse get(String url, Map<String, String> headers) {
                return gets.getOrDefault(url, new IntegrationHttpResponse(404, ""));
            }

            @Override
            public IntegrationHttpResponse post(String url, Map<String, String> headers, String jsonBody) {
                return posts.getOrDefault(url, new IntegrationHttpResponse(404, ""));
            }
        };
        IntegrationConnectService jira = new IntegrationConnectService(http, props, integrations);
        service = new FigmaDesignService(http, integrations, designs, jira, props);
        gets.put(
                "https://api.figma.com/v1/projects/222/files",
                json(200, "{\"files\":[{\"key\":\"AbCdEfGhIj\",\"name\":\"Checkout\",\"thumbnail_url\":\"https://img\",\"last_modified\":\"2026-09-22T10:00:00Z\"}]}")
        );
        gets.put(
                "https://api.figma.com/v1/files/AbCdEfGhIj?depth=2",
                json(200, fileJson("1", "Login", "1:2"))
        );
    }

    @Test
    void parseFileKeyFromDesignUrl() {
        assertThat(FigmaDesignService.parseFileKey("https://www.figma.com/design/AbCdEfGhIj/Checkout"))
                .isEqualTo("AbCdEfGhIj");
        assertThat(FigmaDesignService.parseFileKey("https://www.figma.com/file/AbCdEfGhIj/Checkout?node-id=1-2"))
                .isEqualTo("AbCdEfGhIj");
        assertThat(FigmaDesignService.parseFileKey("AbCdEfGhIj")).isEqualTo("AbCdEfGhIj");
    }

    @Test
    void listFilesUsesStoredFigmaProject() {
        List<FigmaFileItem> files = service.listFiles(new FigmaFilesRequest("42", null, null));
        assertThat(files).extracting(FigmaFileItem::key).containsExactly("AbCdEfGhIj");
        assertThat(files.get(0).name()).isEqualTo("Checkout");
    }

    @Test
    void listFramesReturnsTopLevelScreens() {
        List<FigmaFrameItem> frames = service.listFrames(new FigmaFramesRequest("42", "AbCdEfGhIj", null, null));
        assertThat(frames).extracting(FigmaFrameItem::name).containsExactly("Login");
        assertThat(frames.get(0).pageName()).isEqualTo("Auth");
    }

    @Test
    void ingestStoresSnapshotAndMatchesStoryName() {
        FigmaDesignBindingResponse result = service.ingest(new FigmaIngestRequest(
                "42",
                "AbCdEfGhIj",
                null,
                false,
                List.of(new FigmaStoryRef("st-login", "User can Login")),
                List.of(new FigmaJiraRef("st-login", "FIT-12")),
                null
        ));
        assertThat(result.bound()).isTrue();
        assertThat(result.screens()).hasSize(1);
        assertThat(result.screens().get(0).storyId()).isEqualTo("st-login");
        assertThat(result.screens().get(0).jiraKey()).isEqualTo("FIT-12");
        assertThat(result.markdown()).contains("Login");
        assertThat(result.changes()).extracting(change -> change.kind()).contains("added");
    }

    @Test
    void clearBindingRemovesStoredFile() {
        service.ingest(new FigmaIngestRequest("42", "AbCdEfGhIj", null, false, List.of(), List.of(), null));
        assertThat(service.getBinding("42").bound()).isTrue();
        FigmaDesignBindingResponse cleared = service.clearBinding("42");
        assertThat(cleared.bound()).isFalse();
        assertThat(service.getBinding("42").bound()).isFalse();
        assertThat(service.getBinding("42").screens()).isEmpty();
    }

    @Test
    void secondIngestReportsContentChangeWithoutTouchingJiraWhenSyncOff() {
        service.ingest(new FigmaIngestRequest("42", "AbCdEfGhIj", null, false, List.of(), List.of(), null));
        gets.put(
                "https://api.figma.com/v1/files/AbCdEfGhIj?depth=2",
                json(200, fileJson("2", "Login", "1:2", "Email field"))
        );
        FigmaDesignBindingResponse result = service.ingest(new FigmaIngestRequest(
                "42", "AbCdEfGhIj", null, false, List.of(), List.of(), null
        ));
        assertThat(result.changes()).extracting(change -> change.kind()).contains("updated");
        assertThat(result.lastSyncSummary()).contains("1 screen change");
        assertThat(result.jiraUpdates()).isEmpty();
    }

    @Test
    void fileVersionChangeUpdatesLinkedScreenEvenWhenLayersMatch() {
        service.ingest(new FigmaIngestRequest(
                "42",
                "AbCdEfGhIj",
                null,
                false,
                List.of(new FigmaStoryRef("st-login", "User can Login")),
                List.of(new FigmaJiraRef("st-login", "FIT-12")),
                null
        ));
        gets.put(
                "https://api.figma.com/v1/files/AbCdEfGhIj?depth=2",
                json(200, fileJson("2", "Login", "1:2"))
        );
        FigmaDesignBindingResponse result = service.ingest(new FigmaIngestRequest(
                "42",
                "AbCdEfGhIj",
                null,
                false,
                List.of(new FigmaStoryRef("st-login", "User can Login")),
                List.of(new FigmaJiraRef("st-login", "FIT-12")),
                null
        ));
        assertThat(result.changes()).extracting(change -> change.kind()).contains("updated");
        assertThat(result.changes().get(0).jiraKey()).isEqualTo("FIT-12");
        assertThat(result.lastSyncSummary()).contains("1 screen change");
    }

    @Test
    void webhookSkipsJiraWhenFigmaVersionIsUnchanged() {
        service.ingest(new FigmaIngestRequest("42", "AbCdEfGhIj", null, true, List.of(), List.of(), null));
        StoredFigmaDesign stored = designs.find(42L, "AbCdEfGhIj").orElseThrow();
        designs.upsert(stored.withWebhook("wh-1", "secret-pass", "active"));
        FigmaWebhookResult webhook = service.handleWebhook(
                "{\"event_type\":\"FILE_UPDATE\",\"file_key\":\"AbCdEfGhIj\",\"passcode\":\"secret-pass\"}"
        );
        assertThat(webhook.sync()).isNotNull();
        assertThat(webhook.sync().changes()).isEmpty();
        assertThat(webhook.sync().lastSyncSummary()).contains("version unchanged");
        assertThat(webhook.sync().jiraUpdates()).isEmpty();
    }

    @Test
    void webhookWithPasscodeSyncsBoundFile() {
        FigmaDesignBindingResponse first = service.ingest(new FigmaIngestRequest(
                "42",
                "AbCdEfGhIj",
                null,
                false,
                List.of(),
                List.of(),
                null
        ));
        StoredFigmaDesign stored = designs.find(42L, "AbCdEfGhIj").orElseThrow();
        designs.upsert(stored.withWebhook("wh-1", "secret-pass", "active"));
        gets.put(
                "https://api.figma.com/v1/files/AbCdEfGhIj?depth=2",
                json(200, fileJson("9", "Sign in", "1:2"))
        );
        FigmaWebhookResult webhook = service.handleWebhook(
                "{\"event_type\":\"FILE_UPDATE\",\"file_key\":\"AbCdEfGhIj\",\"passcode\":\"secret-pass\"}"
        );
        assertThat(webhook.status()).isEqualTo("ok");
        assertThat(webhook.sync()).isNotNull();
        assertThat(webhook.sync().changes()).isNotEmpty();
        assertThat(first.fileKey()).isEqualTo("AbCdEfGhIj");
    }

    @Test
    void unknownWebhookPasscodeIsIgnored() {
        FigmaWebhookResult webhook = service.handleWebhook(
                "{\"event_type\":\"FILE_UPDATE\",\"file_key\":\"AbCdEfGhIj\",\"passcode\":\"nope\"}"
        );
        assertThat(webhook.status()).isEqualTo("ignored");
    }

    @Test
    void saveBindingRequiresFile() {
        assertThatThrownBy(() -> service.saveBinding(new FigmaDesignBindingRequest(
                "42", null, null, null, null, true, List.of(), List.of(), List.of(), null
        ))).isInstanceOf(ApiException.class);
    }

    @Test
    void ingestRejectedTokenDoesNotExposeFileKey() {
        gets.put(
                "https://api.figma.com/v1/files/AbCdEfGhIj?depth=2",
                json(403, "{\"status\":403,\"err\":\"Invalid token\"}")
        );
        assertThatThrownBy(() -> service.ingest(new FigmaIngestRequest(
                "42", "AbCdEfGhIj", null, false, List.of(), List.of(), null
        )))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Reconnect Figma")
                .hasMessageNotContaining("AbCdEfGhIj");
    }

    @Test
    void ingestKeepsPreviousThumbnailWhenImagesFail() {
        gets.put(
                "https://api.figma.com/v1/images/AbCdEfGhIj?ids=1%3A2&format=png&scale=1",
                json(200, "{\"images\":{\"1:2\":\"https://figma-thumb/login.png\"}}")
        );
        FigmaDesignBindingResponse first = service.ingest(new FigmaIngestRequest(
                "42", "AbCdEfGhIj", null, false, List.of(), List.of(), null
        ));
        assertThat(first.screens().get(0).thumbnailUrl()).isEqualTo("https://figma-thumb/login.png");

        gets.put(
                "https://api.figma.com/v1/files/AbCdEfGhIj?depth=2",
                json(200, fileJson("2", "Login", "1:2"))
        );
        gets.put(
                "https://api.figma.com/v1/images/AbCdEfGhIj?ids=1%3A2&format=png&scale=1",
                json(429, "{\"status\":429,\"err\":\"Rate limit\"}")
        );
        FigmaDesignBindingResponse second = service.ingest(new FigmaIngestRequest(
                "42", "AbCdEfGhIj", null, false, List.of(), List.of(), null
        ));
        assertThat(second.screens().get(0).thumbnailUrl()).isEqualTo("https://figma-thumb/login.png");
    }

    @Test
    void previewRateLimitPausesTheNextFileRead() {
        service.ingest(new FigmaIngestRequest("42", "AbCdEfGhIj", null, false, List.of(), List.of(), null));
        gets.put(
                "https://api.figma.com/v1/files/AbCdEfGhIj?depth=2",
                json(200, fileJson("2", "Login", "1:2"))
        );
        gets.put(
                "https://api.figma.com/v1/images/AbCdEfGhIj?ids=1%3A2&format=png&scale=1",
                json(429, "{\"status\":429,\"err\":\"Rate limit\"}")
        );
        FigmaDesignBindingResponse second = service.ingest(new FigmaIngestRequest(
                "42", "AbCdEfGhIj", null, false, List.of(), List.of(), null
        ));
        assertThat(second.fileVersion()).isEqualTo("2");
        assertThatThrownBy(() -> service.ingest(new FigmaIngestRequest(
                "42", "AbCdEfGhIj", null, false, List.of(), List.of(), null
        )))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("used up file reads");
    }

    @Test
    void ingestRateLimitKeepsLastSnapshot() {
        FigmaDesignBindingResponse first = service.ingest(new FigmaIngestRequest(
                "42", "AbCdEfGhIj", null, false, List.of(), List.of(), null
        ));
        assertThat(first.bound()).isTrue();
        String previousSummary = first.lastSyncSummary();
        gets.put(
                "https://api.figma.com/v1/files/AbCdEfGhIj?depth=2",
                json(429, "{\"status\":429,\"err\":\"Rate limit exceeded\"}")
        );
        assertThatThrownBy(() -> service.ingest(new FigmaIngestRequest(
                "42", "AbCdEfGhIj", null, false, List.of(), List.of(), null
        )))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("used up file reads");
        FigmaDesignBindingResponse stored = service.getBinding("42");
        assertThat(stored.bound()).isTrue();
        assertThat(stored.screens()).hasSize(1);
        assertThat(stored.lastSyncSummary()).isEqualTo(previousSummary);
        gets.put(
                "https://api.figma.com/v1/files/AbCdEfGhIj?depth=2",
                json(200, fileJson("9", "Should not apply yet", "1:2"))
        );
        assertThatThrownBy(() -> service.ingest(new FigmaIngestRequest(
                "42", "AbCdEfGhIj", null, false, List.of(), List.of(), null
        )))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("used up file reads");
        assertThat(service.getBinding("42").fileVersion()).isEqualTo(first.fileVersion());
    }

    @Test
    void saveBindingKeepsExistingScreensWithoutCallingFigma() {
        service.ingest(new FigmaIngestRequest("42", "AbCdEfGhIj", null, false, List.of(), List.of(), null));
        gets.put(
                "https://api.figma.com/v1/files/AbCdEfGhIj?depth=2",
                json(429, "{\"status\":429,\"err\":\"Rate limit exceeded\"}")
        );
        FigmaDesignBindingResponse saved = service.saveBinding(new FigmaDesignBindingRequest(
                "42", "AbCdEfGhIj", null, null, null, true, List.of(), List.of(), List.of(), null
        ));
        assertThat(saved.screens()).extracting(FigmaScreenBinding::name).containsExactly("Login");
    }

    @Test
    void applyMatchesUsesNormalizedNames() {
        List<FigmaScreenBinding> screens = List.of(new FigmaScreenBinding(
                "1:2", "Login / Welcome", "0:1", "Auth", "FRAME", null, null, "abc", null
        ));
        List<FigmaScreenBinding> matched = FigmaDesignService.applyMatches(
                screens,
                List.of(new FigmaStoryRef("st-1", "login welcome")),
                List.of(new FigmaJiraRef("st-1", "FIT-9"))
        );
        assertThat(matched.get(0).storyId()).isEqualTo("st-1");
        assertThat(matched.get(0).jiraKey()).isEqualTo("FIT-9");
    }

    @Test
    void applyMatchesPairsLeftoverScreensToStoriesInOrder() {
        List<FigmaScreenBinding> screens = List.of(
                new FigmaScreenBinding("1:1", "Frame 1", "0:1", "Page", "FRAME", null, null, "a", null),
                new FigmaScreenBinding("1:2", "Frame 2", "0:1", "Page", "FRAME", null, null, "b", null)
        );
        List<FigmaScreenBinding> matched = FigmaDesignService.applyMatches(
                screens,
                List.of(new FigmaStoryRef("st-home", "Use create fitness app in fitness app"), new FigmaStoryRef("st-two", "Second story")),
                List.of(new FigmaJiraRef("st-home", "AW-1"), new FigmaJiraRef("st-two", "AW-2"))
        );
        assertThat(matched.get(0).storyId()).isEqualTo("st-home");
        assertThat(matched.get(0).jiraKey()).isEqualTo("AW-1");
        assertThat(matched.get(1).storyId()).isEqualTo("st-two");
        assertThat(matched.get(1).jiraKey()).isEqualTo("AW-2");
    }

    private static String fileJson(String version, String frameName, String nodeId) {
        return fileJson(version, frameName, nodeId, "Title");
    }

    private static String fileJson(String version, String frameName, String nodeId, String childName) {
        return """
                {
                  "name": "Checkout",
                  "version": "%s",
                  "document": {
                    "children": [
                      {
                        "id": "0:1",
                        "name": "Auth",
                        "type": "CANVAS",
                        "children": [
                          {
                            "id": "%s",
                            "name": "%s",
                            "type": "FRAME",
                            "children": [{"id":"9:9","name":"%s","type":"TEXT"}]
                          }
                        ]
                      }
                    ]
                  }
                }
                """.formatted(version, nodeId, frameName, childName);
    }

    private static IntegrationHttpGateway.IntegrationHttpResponse json(int status, String body) {
        return new IntegrationHttpGateway.IntegrationHttpResponse(status, body);
    }

    private static final class MemoryProjectIntegrationStore implements ProjectIntegrationStore {
        private final Map<String, StoredIntegration> rows = new HashMap<>();

        @Override
        public void upsert(StoredIntegration integration) {
            if (integration == null || integration.projectId() == null || integration.provider() == null) {
                return;
            }
            rows.put(integration.projectId() + ":" + integration.provider(), integration);
        }

        @Override
        public java.util.Optional<StoredIntegration> find(Long projectId, String provider) {
            if (projectId == null || provider == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.ofNullable(rows.get(projectId + ":" + provider));
        }

        @Override
        public java.util.Optional<StoredIntegration> findLatest(String provider) {
            if (provider == null || provider.isBlank()) {
                return java.util.Optional.empty();
            }
            StoredIntegration best = null;
            for (StoredIntegration row : rows.values()) {
                if (row == null || row.provider() == null || !provider.equalsIgnoreCase(row.provider())) {
                    continue;
                }
                if (best == null || (row.projectId() != null && (best.projectId() == null || row.projectId() > best.projectId()))) {
                    best = row;
                }
            }
            return java.util.Optional.ofNullable(best);
        }
    }
}
