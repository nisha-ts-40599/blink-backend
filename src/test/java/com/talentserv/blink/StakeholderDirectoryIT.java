package com.talentserv.blink;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.talentserv.blink.service.RoleCatalog;

@SpringBootTest
class StakeholderDirectoryIT {

    @Autowired
    private RoleCatalog roleCatalog;

    @Test
    void loadsPeopleFromStakeholdersYaml() {
        assertThat(roleCatalog.roles()).hasSize(14);
        assertThat(roleCatalog.roles().getFirst().roleCode()).isEqualTo("product_owner");
        assertThat(roleCatalog.roles().getFirst().defaultName()).isEqualTo("Rohit Naik");
        assertThat(roleCatalog.roles().getFirst().defaultEmail()).isEqualTo("rohit.naik@talentserv.co.in");
        assertThat(roleCatalog.nameFor("backend_developer")).isEqualTo("Backend Developer");
    }
}
