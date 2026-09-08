package com.zhulikang.aimatch;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIT {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("ai_resume_match")
        .withUsername("test")
        .withPassword("test");

    @Test
    void migratesLegacyJobTitleAndEnforcesConstraintsOnMySql84() throws Exception {
        Flyway.configure()
            .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
            .target(MigrationVersion.fromVersion("1"))
            .load()
            .migrate();

        long jobId;
        try (Connection connection = connection()) {
            jobId = insertLegacyJob(connection);
        }

        Flyway.configure()
            .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
            .load()
            .migrate();

        try (Connection connection = connection()) {
            assertThat(tableExists(connection, "analysis_task")).isTrue();
            assertThat(tableExists(connection, "match_report")).isTrue();
            assertThat(tableExists(connection, "analysis_outbox")).isTrue();
            assertThat(indexExists(connection, "analysis_outbox", "idx_analysis_outbox_due")).isTrue();
            assertThat(indexExists(connection, "analysis_outbox", "idx_analysis_outbox_lease")).isTrue();
            assertThat(indexExists(connection, "analysis_outbox", "idx_analysis_outbox_terminal")).isTrue();
            assertThat(indexExists(
                connection,
                "analysis_submission_idempotency",
                "idx_analysis_idempotency_created"
            )).isTrue();
            assertThat(indexExists(connection, "analysis_task", "idx_analysis_task_created_id")).isTrue();
            assertThat(indexExists(
                connection,
                "analysis_task",
                "idx_analysis_task_status_created_id"
            )).isTrue();
            assertThat(indexExists(
                connection,
                "analysis_task",
                "idx_analysis_task_owner_status_created"
            )).isTrue();
            assertThat(indexExists(connection, "resume", "idx_resume_owner")).isTrue();
            assertThat(columnExists(connection, "resume", "structured_summary")).isFalse();
            assertThat(jobTitle(connection, jobId)).isEqualTo("岗位 " + jobId);
            assertThat(stringValue(connection, "job_description", "owner_id", jobId))
                .isEqualTo("0".repeat(64));

            ColumnMetadata titleColumn = columnMetadata(connection, "job_description", "title");
            assertThat(titleColumn.length()).isEqualTo(120);
            assertThat(titleColumn.nullability()).isEqualTo(DatabaseMetaData.columnNoNulls);
            ColumnMetadata leaseTokenColumn = columnMetadata(
                connection,
                "analysis_outbox",
                "lease_token"
            );
            assertThat(leaseTokenColumn.length()).isEqualTo(36);
            assertThat(leaseTokenColumn.nullability()).isEqualTo(DatabaseMetaData.columnNullable);
            assertThat(columnMetadata(connection, "analysis_outbox", "lease_until").nullability())
                .isEqualTo(DatabaseMetaData.columnNullable);
            assertThat(columnMetadata(connection, "analysis_outbox", "terminal_at").nullability())
                .isEqualTo(DatabaseMetaData.columnNullable);
            ColumnMetadata ownerColumn = columnMetadata(connection, "analysis_task", "owner_id");
            assertThat(ownerColumn.length()).isEqualTo(64);
            assertThat(ownerColumn.nullability()).isEqualTo(DatabaseMetaData.columnNoNulls);
            assertThatThrownBy(() -> insertJobWithNullTitle(connection))
                .isInstanceOf(SQLException.class);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
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

    private String stringValue(Connection connection, String table, String column, long id)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select " + column + " from " + table + " where id = ?"
        )) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try (ResultSet rows = connection.getMetaData().getTables(null, null, tableName, null)) {
            return rows.next();
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws Exception {
        try (ResultSet rows = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
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
