package com.zhulikang.aimatch;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationTest {
    @Test
    void migratesCoreTablesAndOutboxOnH2MySqlMode() throws Exception {
        String url = "jdbc:h2:mem:flyway_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure()
            .dataSource(url, "sa", "")
            .load()
            .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(tableExists(connection, "analysis_task")).isTrue();
            assertThat(tableExists(connection, "match_report")).isTrue();
            assertThat(tableExists(connection, "analysis_outbox")).isTrue();
            assertThat(indexExists(connection, "analysis_outbox", "idx_analysis_outbox_due")).isTrue();
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try (ResultSet rows = connection.getMetaData().getTables(null, null, tableName, null)) {
            return rows.next();
        }
    }

    private boolean indexExists(Connection connection, String tableName, String indexName) throws Exception {
        try (ResultSet rows = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            while (rows.next()) {
                if (indexName.equals(rows.getString("INDEX_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
