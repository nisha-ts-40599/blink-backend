package com.talentserv.blink.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "project")
public class Project extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_name", nullable = false, length = 255)
    private String projectName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "project_type", length = 30)
    private String projectType;

    @Column(name = "owner_email", length = 255)
    private String ownerEmail;

    @Column(name = "wizard_step", length = 80)
    private String wizardStep;

    @Column(name = "wizard_completed_through")
    private Integer wizardCompletedThrough;

    @Column(name = "wizard_state_json", columnDefinition = "TEXT")
    private String wizardStateJson;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getProjectType() {
        return projectType;
    }

    public void setProjectType(String projectType) {
        this.projectType = projectType;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }

    public String getWizardStep() {
        return wizardStep;
    }

    public void setWizardStep(String wizardStep) {
        this.wizardStep = wizardStep;
    }

    public Integer getWizardCompletedThrough() {
        return wizardCompletedThrough;
    }

    public void setWizardCompletedThrough(Integer wizardCompletedThrough) {
        this.wizardCompletedThrough = wizardCompletedThrough;
    }

    public String getWizardStateJson() {
        return wizardStateJson;
    }

    public void setWizardStateJson(String wizardStateJson) {
        this.wizardStateJson = wizardStateJson;
    }
}
