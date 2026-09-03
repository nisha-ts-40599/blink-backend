package com.talentserv.blink.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

import com.talentserv.blink.repo.ProjectRepository;
import com.talentserv.blink.repo.StakeholderRepository;

@RestController
@Profile("!nodb")
@RequestMapping("/api/db-status")
public class DbStatusController {

    private final ProjectRepository projectRepository;
    private final StakeholderRepository stakeholderRepository;
    private final JdbcTemplate jdbcTemplate;

    public DbStatusController(
            ProjectRepository projectRepository,
            StakeholderRepository stakeholderRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.projectRepository = projectRepository;
        this.stakeholderRepository = stakeholderRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("connected", true);
        body.put("projects", projectRepository.count());
        body.put("stakeholders", stakeholderRepository.count());
        body.put("projectColumns", columns("project"));
        body.put("stakeholderColumns", columns("stakeholder"));
        body.put("projectChecks", checkConstraints("project"));
        return body;
    }

    private List<String> checkConstraints(String table) {
        return jdbcTemplate.query(
                """
                select pg_get_constraintdef(con.oid)
                from pg_constraint con
                join pg_class rel on rel.oid = con.conrelid
                join pg_namespace nsp on nsp.oid = rel.relnamespace
                where nsp.nspname = current_schema()
                  and rel.relname = ?
                  and con.contype = 'c'
                order by con.conname
                """,
                (rs, rowNum) -> rs.getString(1),
                table);
    }

    private List<String> columns(String table) {
        return jdbcTemplate.query(
                """
                select column_name || ':' || udt_name
                from information_schema.columns
                where table_schema = current_schema()
                  and table_name = ?
                order by ordinal_position
                """,
                (rs, rowNum) -> rs.getString(1),
                table);
    }
}
