package io.jmix.ai.backend.checks;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheckRunVersionMigrationTest {

    @Test
    void correctiveMigrationChangesOnlyRunsCoveredByOriginalBackfill() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        org.w3c.dom.Document document;
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream(
                "/io/jmix/ai/backend/liquibase/changelog/2026/07/10-210000-correct-legacy-check-run-version.xml"))) {
            document = factory.newDocumentBuilder().parse(resource);
        }

        Element update = (Element) document.getElementsByTagNameNS("*", "update").item(0);
        Element column = (Element) update.getElementsByTagNameNS("*", "column").item(0);
        String where = update.getElementsByTagNameNS("*", "where").item(0)
                .getTextContent().replaceAll("\\s+", " ").trim();

        assertThat(update.getAttribute("tableName")).isEqualTo("CHECK_RUN");
        assertThat(column.getAttribute("name")).isEqualTo("JMIX_VERSION");
        assertThat(column.getAttribute("value")).isEqualTo("v3");
        assertThat(where)
                .contains("JMIX_VERSION = 'v2'")
                .contains("CREATED_DATE <= ( SELECT DATEEXECUTED")
                .contains("ID = '1'")
                .contains("AUTHOR = 'jmix-ai-backend'")
                .contains("FILENAME = 'io/jmix/ai/backend/liquibase/changelog/2026/07/08-110000-check-run-jmix-version.xml'");

        verifyUpdateSemantics(where, column.getAttribute("value"));
    }

    private static void verifyUpdateSemantics(String where, String correctedVersion) throws Exception {
        String database = "jdbc:hsqldb:mem:check_run_migration_" + UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(database, "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create table CHECK_RUN (RUN_ID varchar(20) primary key, "
                        + "JMIX_VERSION varchar(10), CREATED_DATE timestamp)");
                statement.execute("create table DATABASECHANGELOG (ID varchar(20), AUTHOR varchar(100), "
                        + "FILENAME varchar(500), DATEEXECUTED timestamp)");
            }

            LocalDateTime migrationTime = LocalDateTime.of(2026, 7, 8, 11, 0);
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into DATABASECHANGELOG (ID, AUTHOR, FILENAME, DATEEXECUTED) values (?, ?, ?, ?)")) {
                statement.setString(1, "1");
                statement.setString(2, "jmix-ai-backend");
                statement.setString(3,
                        "io/jmix/ai/backend/liquibase/changelog/2026/07/08-110000-check-run-jmix-version.xml");
                statement.setTimestamp(4, Timestamp.valueOf(migrationTime));
                statement.executeUpdate();
            }

            insertRun(connection, "old-v2", "v2", migrationTime.minusMinutes(1));
            insertRun(connection, "new-v2", "v2", migrationTime.plusMinutes(1));
            insertRun(connection, "old-v3", "v3", migrationTime.minusMinutes(1));

            try (Statement statement = connection.createStatement()) {
                assertThat(statement.executeUpdate("update CHECK_RUN set JMIX_VERSION = '"
                        + correctedVersion + "' where " + where)).isEqualTo(1);
            }

            Map<String, String> versions = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "select RUN_ID, JMIX_VERSION from CHECK_RUN order by RUN_ID")) {
                while (resultSet.next()) {
                    versions.put(resultSet.getString(1), resultSet.getString(2));
                }
            }
            assertThat(versions).containsExactly(
                    Map.entry("new-v2", "v2"),
                    Map.entry("old-v2", "v3"),
                    Map.entry("old-v3", "v3"));
        }
    }

    private static void insertRun(Connection connection, String id, String version,
                                  LocalDateTime createdDate) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into CHECK_RUN (RUN_ID, JMIX_VERSION, CREATED_DATE) values (?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, version);
            statement.setTimestamp(3, Timestamp.valueOf(createdDate));
            statement.executeUpdate();
        }
    }
}
