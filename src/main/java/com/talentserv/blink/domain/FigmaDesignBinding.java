package com.talentserv.blink.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "figma_design_binding",
        uniqueConstraints = @UniqueConstraint(name = "uq_figma_design_project_file", columnNames = {"project_id", "file_key"})
)
public class FigmaDesignBinding extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "file_key", nullable = false, length = 80)
    private String fileKey;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_url", length = 500)
    private String fileUrl;

    @Column(name = "figma_project_id", length = 80)
    private String figmaProjectId;

    @Column(name = "team_id", length = 80)
    private String teamId;

    @Column(name = "sync_jira", nullable = false)
    private boolean syncJira = true;

    @Column(name = "webhook_id", length = 120)
    private String webhookId;

    @Column(name = "webhook_passcode", length = 120)
    private String webhookPasscode;

    @Column(name = "file_version", length = 80)
    private String fileVersion;

    @Column(name = "last_synced_at", length = 40)
    private String lastSyncedAt;

    @Column(name = "last_sync_summary", columnDefinition = "TEXT")
    private String lastSyncSummary;

    @Column(name = "webhook_status", length = 120)
    private String webhookStatus;

    @Column(name = "snapshot_json", columnDefinition = "TEXT")
    private String snapshotJson;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getFileKey() {
        return fileKey;
    }

    public void setFileKey(String fileKey) {
        this.fileKey = fileKey;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public String getFigmaProjectId() {
        return figmaProjectId;
    }

    public void setFigmaProjectId(String figmaProjectId) {
        this.figmaProjectId = figmaProjectId;
    }

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public boolean isSyncJira() {
        return syncJira;
    }

    public void setSyncJira(boolean syncJira) {
        this.syncJira = syncJira;
    }

    public String getWebhookId() {
        return webhookId;
    }

    public void setWebhookId(String webhookId) {
        this.webhookId = webhookId;
    }

    public String getWebhookPasscode() {
        return webhookPasscode;
    }

    public void setWebhookPasscode(String webhookPasscode) {
        this.webhookPasscode = webhookPasscode;
    }

    public String getFileVersion() {
        return fileVersion;
    }

    public void setFileVersion(String fileVersion) {
        this.fileVersion = fileVersion;
    }

    public String getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(String lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public String getLastSyncSummary() {
        return lastSyncSummary;
    }

    public void setLastSyncSummary(String lastSyncSummary) {
        this.lastSyncSummary = lastSyncSummary;
    }

    public String getWebhookStatus() {
        return webhookStatus;
    }

    public void setWebhookStatus(String webhookStatus) {
        this.webhookStatus = webhookStatus;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
    }
}
