package com.talentserv.blink.web;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.talentserv.blink.dto.StakeholderRoleResponse;
import com.talentserv.blink.service.RoleCatalog;

@RestController
@RequestMapping("/api/stakeholder-roles")
public class StakeholderRoleController {

    private final RoleCatalog roleCatalog;

    public StakeholderRoleController(RoleCatalog roleCatalog) {
        this.roleCatalog = roleCatalog;
    }

    @GetMapping
    public ResponseEntity<List<StakeholderRoleResponse>> list() {
        return ResponseEntity.ok()
                .header("X-Blink-Stakeholder-Source", "stakeholders.yaml")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(roleCatalog.roles());
    }
}
