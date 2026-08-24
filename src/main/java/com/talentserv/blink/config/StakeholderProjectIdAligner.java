package com.talentserv.blink.config;

import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "spring.jpa.hibernate.ddl-auto", havingValue = "none", matchIfMissing = true)
public class StakeholderProjectIdAligner implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(StakeholderProjectIdAligner.class);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public StakeholderProjectIdAligner(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        try (var connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            if (url != null && url.toLowerCase().contains("h2")) {
                return;
            }
        }
        log.info(
                "stakeholder column types: id={}, project_id={}",
                columnType("stakeholder", "id"),
                columnType("stakeholder", "project_id"));
        String type = columnType("stakeholder", "project_id");
        if (type == null) {
            jdbcTemplate.execute("ALTER TABLE stakeholder ADD COLUMN project_id BIGINT");
            log.info("Added stakeholder.project_id as BIGINT");
            return;
        }
        if (!"uuid".equalsIgnoreCase(type)) {
            return;
        }

        dropForeignKeys("stakeholder", "project_id");
        try {
            jdbcTemplate.execute("ALTER TABLE stakeholder ALTER COLUMN project_id DROP DEFAULT");
        } catch (RuntimeException ignored) {
            // column may not have a default
        }
        try {
            jdbcTemplate.execute("ALTER TABLE stakeholder ALTER COLUMN project_id DROP NOT NULL");
        } catch (RuntimeException ignored) {
            // column may already allow null
        }
        try {
            jdbcTemplate.execute("ALTER TABLE stakeholder ALTER COLUMN project_id TYPE BIGINT USING NULL::bigint");
        } catch (RuntimeException ex) {
            log.warn("ALTER TYPE failed; dropping and recreating stakeholder.project_id: {}", ex.getMessage());
            jdbcTemplate.execute("ALTER TABLE stakeholder DROP COLUMN project_id");
            jdbcTemplate.execute("ALTER TABLE stakeholder ADD COLUMN project_id BIGINT");
        }
        log.info("Converted stakeholder.project_id from UUID to BIGINT");
    }

    private String columnType(String table, String column) {
        List<String> types = jdbcTemplate.query(
                """
                select udt_name
                from information_schema.columns
                where table_schema = current_schema()
                  and table_name = ?
                  and column_name = ?
                """,
                (rs, rowNum) -> rs.getString(1),
                table,
                column);
        return types.isEmpty() ? null : types.getFirst();
    }

    private void dropForeignKeys(String table, String column) {
        List<String> names = jdbcTemplate.query(
                """
                select con.conname
                from pg_constraint con
                join pg_class rel on rel.oid = con.conrelid
                join pg_namespace nsp on nsp.oid = rel.relnamespace
                join pg_attribute att on att.attrelid = rel.oid and att.attnum = any (con.conkey)
                where nsp.nspname = current_schema()
                  and rel.relname = ?
                  and att.attname = ?
                  and con.contype = 'f'
                """,
                (rs, rowNum) -> rs.getString(1),
                table,
                column);
        for (String name : names) {
            if (!name.matches("[A-Za-z0-9_]+")) {
                continue;
            }
            jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + name);
        }
    }
}
