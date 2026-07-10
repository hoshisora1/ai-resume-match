package com.zhulikang.aimatch;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayMigrationTest {
    @Test
    void migratesLegacyJobTitleAndEnforcesConstraintsOnH2MySqlMode() throws Exception {
        String url = "jdbc:h2:mem:flyway_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure()
            .dataSource(url, "sa", "")
            .target(MigrationVersion.fromVersion("1"))
            .load()
            .migrate();

        long jobId;
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            jobId = insertLegacyJob(connection);
        }

        Flyway.configure()
            .dataSource(url, "sa", "")
            .load()
            .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(tableExists(connection, "analysis_task")).isTrue();
            assertThat(tableExists(connection, "match_report")).isTrue();
            assertThat(tableExists(connection, "analysis_outbox")).isTrue();
            assertThat(indexExists(connection, "analysis_outbox", "idx_analysis_outbox_due")).isTrue();
            assertThat(indexExists(connection, "analysis_task", "idx_analysis_task_created_id")).isTrue();
            assertThat(indexExists(
                connection,
                "analysis_task",
                "idx_analysis_task_status_created_id"
            )).isTrue();
            assertThat(jobTitle(connection, jobId)).isEqualTo("岗位 " + jobId);

            ColumnMetadata titleColumn = columnMetadata(connection, "job_description", "title");
            assertThat(titleColumn.length()).isEqualTo(120);
            assertThat(titleColumn.nullability()).isEqualTo(DatabaseMetaData.columnNoNulls);
            assertThatThrownBy(() -> insertJobWithNullTitle(connection))
                .isInstanceOf(SQLException.class);
        }
    }

    private long insertLegacyJob(Connection connection) throws SQLException {
        String sql = "insert into job_description (content, skill_tags, created_at) "
            + "values (?, ?, current_timestamp)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "Legacy Java role");
            statement.setString(2, "Java");
            assertThat(statement.executeUpdate()).isOne();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private String jobTitle(Connection connection, long jobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select title from job_description where id = ?"
        )) {
            statement.setLong(1, jobId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString("title");
            }
        }
    }

    private ColumnMetadata columnMetadata(Connection connection, String tableName, String columnName)
        throws SQLException {
        try (ResultSet rows = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            assertThat(rows.next()).isTrue();
            return new ColumnMetadata(rows.getInt("COLUMN_SIZE"), rows.getInt("NULLABLE"));
        }
    }

    private void insertJobWithNullTitle(Connection connection) throws SQLException {
        String sql = "insert into job_description (title, content, skill_tags, created_at) "
            + "values (?, ?, ?, current_timestamp)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setNull(1, java.sql.Types.VARCHAR);
            statement.setString(2, "Invalid role");
            statement.setString(3, "Java");
            statement.executeUpdate();
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

    private record ColumnMetadata(int length, int nullability) {
    }
}
