package com.zhulikang.aimatch;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FlywayMigrationIT {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("ai_resume_match")
        .withUsername("test")
        .withPassword("test");

    @Autowired
    DataSource dataSource;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.autoconfigure.exclude", () -> String.join(",",
            "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
        ));
        registry.add("api.token", () -> "test-token");
        registry.add("ai.api-key", () -> "test-key");
        registry.add("ai.endpoint", () -> "http://localhost/mock");
    }

    @Test
    void migratesCoreTablesAndOutbox() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableExists(connection, "analysis_task")).isTrue();
            assertThat(tableExists(connection, "match_report")).isTrue();
            assertThat(tableExists(connection, "analysis_outbox")).isTrue();
            assertThat(columnExists(connection, "job_description", "title")).isTrue();
            assertThat(indexExists(connection, "analysis_outbox", "idx_analysis_outbox_due")).isTrue();
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
}
