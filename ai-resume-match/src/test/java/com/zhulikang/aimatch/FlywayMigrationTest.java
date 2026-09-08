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
        long reportId;
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            jobId = insertLegacyJob(connection);
            reportId = insertLegacyReport(connection, jobId);
        }

        Flyway.configure()
            .dataSource(url, "sa", "")
            .load()
            .migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(tableExists(connection, "analysis_task")).isTrue();
            assertThat(tableExists(connection, "match_report")).isTrue();
            assertThat(tableExists(connection, "analysis_outbox")).isTrue();
            assertThat(tableExists(connection, "analysis_submission_idempotency")).isTrue();
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
            assertThat(reportSchemaVersion(connection, reportId)).isEqualTo("markdown-v1");

            ColumnMetadata titleColumn = columnMetadata(connection, "job_description", "title");
            assertThat(titleColumn.length()).isEqualTo(120);
            assertThat(titleColumn.nullability()).isEqualTo(DatabaseMetaData.columnNoNulls);
            assertThatThrownBy(() -> insertJobWithNullTitle(connection))
                .isInstanceOf(SQLException.class);

            ColumnMetadata keyHashColumn = columnMetadata(
                connection,
                "analysis_submission_idempotency",
                "idempotency_key_hash"
            );
            assertThat(keyHashColumn.length()).isEqualTo(64);
            assertThat(keyHashColumn.nullability()).isEqualTo(DatabaseMetaData.columnNoNulls);

            ColumnMetadata ownerColumn = columnMetadata(connection, "analysis_task", "owner_id");
            assertThat(ownerColumn.length()).isEqualTo(64);
            assertThat(ownerColumn.nullability()).isEqualTo(DatabaseMetaData.columnNoNulls);

            ColumnMetadata reportVersionColumn = columnMetadata(
                connection,
                "match_report",
                "report_schema_version"
            );
            assertThat(reportVersionColumn.length()).isEqualTo(32);
            assertThat(reportVersionColumn.nullability()).isEqualTo(DatabaseMetaData.columnNoNulls);

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

            insertIdempotencyRecord(connection, "a".repeat(64), "b".repeat(64));
            assertThatThrownBy(() -> insertIdempotencyRecord(
                connection,
                "a".repeat(64),
                "c".repeat(64)
            )).isInstanceOf(SQLException.class);
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

    private long insertLegacyReport(Connection connection, long jobId) throws SQLException {
        long resumeId;
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into resume (file_name, raw_text, structured_summary, created_at) "
                + "values ('legacy.pdf', 'Java', 'Java', current_timestamp)",
            Statement.RETURN_GENERATED_KEYS
        )) {
            assertThat(statement.executeUpdate()).isOne();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                resumeId = keys.getLong(1);
            }
        }

        long taskId;
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into analysis_task "
                + "(resume_id, job_description_id, status, created_at, updated_at) "
                + "values (?, ?, 'SUCCESS', current_timestamp, current_timestamp)",
            Statement.RETURN_GENERATED_KEYS
        )) {
            statement.setLong(1, resumeId);
            statement.setLong(2, jobId);
            assertThat(statement.executeUpdate()).isOne();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                taskId = keys.getLong(1);
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(
            "insert into match_report (task_id, match_score, report_content, created_at) "
                + "values (?, 80, 'legacy report', current_timestamp)",
            Statement.RETURN_GENERATED_KEYS
        )) {
            statement.setLong(1, taskId);
            assertThat(statement.executeUpdate()).isOne();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private String reportSchemaVersion(Connection connection, long reportId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select report_schema_version from match_report where id = ?"
        )) {
            statement.setLong(1, reportId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString("report_schema_version");
            }
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

    private void insertIdempotencyRecord(
        Connection connection,
        String keyHash,
        String requestFingerprint
    ) throws SQLException {
        String sql = "insert into analysis_submission_idempotency "
            + "(idempotency_key_hash, request_fingerprint, created_at) "
            + "values (?, ?, current_timestamp)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, keyHash);
            statement.setString(2, requestFingerprint);
            statement.executeUpdate();
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
