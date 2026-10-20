package interview.guide.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.config.OwnershipMigrationConfiguration;
import interview.guide.modules.auth.service.UsernameNormalizer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("业务根资源归属迁移")
class ResourceOwnershipMigrationTest {
  private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final List<String> ROOTS = List.of("resumes", "interview_sessions",
      "voice_interview_sessions", "knowledge_bases", "rag_chat_sessions", "interview_schedule");

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Test
  @DisplayName("空库无需旧数据所有者即可迁移并约束六个根表的归属外键")
  void migratesEmptyDatabaseWithoutLegacyAccount() {
    var fixture = database();
    assertThat(flyway(fixture, "").migrate().migrationsExecuted).isEqualTo(3);
    for (String table : ROOTS) {
      assertThat(fixture.jdbc().queryForObject("""
          select is_nullable from information_schema.columns
          where table_schema = 'public' and table_name = ? and column_name = 'owner_user_id'
          """, String.class, table)).isEqualTo("NO");
      assertThat(fixture.jdbc().queryForObject("""
          select count(*) from pg_constraint where conrelid = ?::regclass
          and contype = 'f' and confrelid = 'users'::regclass
          """, Integer.class, table)).isOne();
      assertThatThrownBy(() -> insertRoot(fixture.jdbc(), table, null))
          .hasMessageContaining("owner_user_id");
      assertThatThrownBy(() -> insertRoot(fixture.jdbc(), table, OWNER))
          .hasMessageContaining("foreign key");
    }
    assertThat(flyway(fixture, "").migrate().migrationsExecuted).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"resumes", "interview_sessions", "voice_interview_sessions",
      "knowledge_bases", "rag_chat_sessions", "interview_schedule"})
  @DisplayName("任何一个旧根表非空都要求指定且唯一的现有所有者，失败完整回滚")
  void failsClosedWhenAnyRootHasDataAndOwnerIsMissing(String table) {
    var fixture = legacyDatabase();
    insertRoot(fixture.jdbc(), table, null);
    insertUser(fixture.jdbc(), OTHER, "other");
    assertThatThrownBy(() -> flyway(fixture, "missing").migrate())
        .hasStackTraceContaining("exactly one existing legacy owner");
    assertRolledBack(fixture, table);
    insertUser(fixture.jdbc(), OWNER, "missing");
    assertThat(flyway(fixture, "missing").migrate().migrationsExecuted).isOne();
    assertThat(fixture.jdbc().queryForObject("select owner_user_id from " + table, UUID.class))
        .isEqualTo(OWNER);
  }

  @Test
  @DisplayName("旧数据无法解析唯一所有者时拒绝空配置与损坏的重复登录名")
  void rejectsEmptyAndAmbiguousLegacyOwners() {
    var fixture = legacyDatabase();
    insertRoot(fixture.jdbc(), "resumes", null);
    assertThatThrownBy(() -> flyway(fixture, "").migrate())
        .hasStackTraceContaining("exactly one existing legacy owner");
    assertRolledBack(fixture, "resumes");
    fixture.jdbc().execute("alter table users drop constraint users_normalized_username_key");
    insertUser(fixture.jdbc(), OWNER, "legacy");
    insertUser(fixture.jdbc(), OTHER, "legacy");
    assertThatThrownBy(() -> flyway(fixture, "legacy").migrate())
        .hasStackTraceContaining("exactly one existing legacy owner");
    assertRolledBack(fixture, "resumes");
  }

  @Test
  @DisplayName("全部旧根记录及关联保留并归属指定账号，不按语音历史 user_id 猜测")
  void backfillsEveryRootAndPreservesRelationships() {
    var fixture = legacyDatabase();
    insertUser(fixture.jdbc(), OWNER, "legacy");
    insertUser(fixture.jdbc(), OTHER, "other");
    ROOTS.forEach(table -> insertRoot(fixture.jdbc(), table, null));
    fixture.jdbc().update("update interview_sessions set resume_id = 1");
    fixture.jdbc().update("update voice_interview_sessions set user_id = 'other', resume_id = 1");
    fixture.jdbc().update("insert into rag_session_knowledge_bases values (1, 1)");

    assertThat(flyway(fixture, "legacy").migrate().migrationsExecuted).isOne();
    for (String table : ROOTS) {
      assertThat(fixture.jdbc().queryForList("select owner_user_id from " + table, UUID.class))
          .containsExactly(OWNER);
    }
    assertThat(fixture.jdbc().queryForObject("select resume_id from interview_sessions", Long.class))
        .isEqualTo(1L);
    assertThat(fixture.jdbc().queryForObject("select user_id from voice_interview_sessions", String.class))
        .isEqualTo("other");
    assertThat(fixture.jdbc().queryForObject("select count(*) from rag_session_knowledge_bases",
        Integer.class)).isOne();
    assertThatThrownBy(() -> fixture.jdbc().update("delete from users where id = ?", OWNER))
        .hasMessageContaining("foreign key");
  }

  @ParameterizedTest
  @ValueSource(strings = {"resumes", "knowledge_bases"})
  @DisplayName("去重哈希同用户冲突、跨用户可重复，兼容旧 Hibernate 命名的唯一约束和索引")
  void scopesHashUniquenessToOwner(String table) {
    var fixture = legacyDatabase();
    insertUser(fixture.jdbc(), OWNER, "legacy");
    insertUser(fixture.jdbc(), OTHER, "other");
    fixture.jdbc().execute("alter table " + table + " rename constraint " + table
        + "_file_hash_key to arbitrary_legacy_hash_constraint");
    fixture.jdbc().execute("create unique index arbitrary_legacy_hash_index on " + table
        + " (file_hash)");
    insertRoot(fixture.jdbc(), table, null);
    flyway(fixture, "legacy").migrate();
    insertRoot(fixture.jdbc(), table, OTHER);
    assertThatThrownBy(() -> insertRoot(fixture.jdbc(), table, OWNER))
        .hasMessageContaining("duplicate key");
    assertThat(fixture.jdbc().queryForObject("select count(*) from " + table, Integer.class))
        .isEqualTo(2);
  }

  private void assertRolledBack(Fixture fixture, String table) {
    assertThat(flyway(fixture, "").info().current().getVersion().getVersion()).isEqualTo("2");
    assertThat(fixture.jdbc().queryForObject("""
        select count(*) from information_schema.columns
        where table_schema = 'public' and column_name = 'owner_user_id'
        """, Integer.class)).isZero();
    assertThat(fixture.jdbc().queryForObject("select count(*) from " + table, Integer.class)).isOne();
  }

  @Test
  @DisplayName("回填后索引创建失败仍回滚全部归属与唯一约束修改，修复冲突可重试")
  void rollsBackLateDdlFailureAndCanRetry() {
    var fixture = legacyDatabase();
    insertUser(fixture.jdbc(), OWNER, "legacy");
    insertRoot(fixture.jdbc(), "resumes", null);
    fixture.jdbc().execute("create index idx_voice_owner_updated on voice_interview_sessions (created_at)");

    assertThatThrownBy(() -> flyway(fixture, "legacy").migrate())
        .hasStackTraceContaining("idx_voice_owner_updated");
    assertRolledBack(fixture, "resumes");
    assertThatThrownBy(() -> insertRoot(fixture.jdbc(), "resumes", null))
        .hasMessageContaining("duplicate key");
    fixture.jdbc().execute("drop index idx_voice_owner_updated");
    assertThat(flyway(fixture, "legacy").migrate().migrationsExecuted).isOne();
    assertThat(fixture.jdbc().queryForObject("select owner_user_id from resumes", UUID.class))
        .isEqualTo(OWNER);
  }

  @ParameterizedTest
  @ValueSource(strings = {" ＬＥＧＡＣＹ ", " O'Brien ", "${missing}", "$ownership$",
      "$$; DROP TABLE users;--", "管理员Ａ", "${review.test.value}",
      "${review.test.value:default-user}"})
  @DisplayName("应用安全编码后的引号、占位符、dollar quote 和 Unicode 登录名可执行 V3")
  void migratesWithSafelyEncodedConfiguredUsername(String configured) {
    String normalized = switch (configured) {
      case " ＬＥＧＡＣＹ " -> "legacy";
      case " O'Brien " -> "o'brien";
      case "$$; DROP TABLE users;--" -> "$$; drop table users;--";
      case "管理员Ａ" -> "管理员a";
      default -> configured;
    };
    var fixture = legacyDatabase();
    insertUser(fixture.jdbc(), OWNER, normalized);
    insertUser(fixture.jdbc(), OTHER, "other-user");
    insertRoot(fixture.jdbc(), "resumes", null);
    var configuration = Flyway.configure().dataSource(fixture.url(), POSTGRES.getUsername(),
        POSTGRES.getPassword());
    var environment = new StandardEnvironment();
    environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            Map.of("APP_LEGACY_OWNER_USERNAME", configured)));
    environment.getPropertySources().addFirst(new MapPropertySource("resolvable-review-value",
        Map.of("review.test.value", "other-user")));
    new OwnershipMigrationConfiguration().ownershipMigrationCustomizer(
        environment, new UsernameNormalizer()).customize(configuration);

    assertThat(configuration.load().migrate().migrationsExecuted).isOne();
    assertThat(fixture.jdbc().queryForObject("select owner_user_id from resumes", UUID.class))
        .isEqualTo(OWNER);
    assertThat(fixture.jdbc().queryForObject("select count(*) from users", Integer.class)).isEqualTo(2);
  }

  private Fixture legacyDatabase() {
    var fixture = database();
    Flyway.configure().dataSource(fixture.url(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .target("2").load().migrate();
    return fixture;
  }

  private Fixture database() {
    String name = "ownership_" + UUID.randomUUID().toString().replace("-", "");
    var admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(), POSTGRES.getPassword()));
    admin.execute("create database " + name);
    String url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
    return new Fixture(url, new JdbcTemplate(new DriverManagerDataSource(url,
        POSTGRES.getUsername(), POSTGRES.getPassword())));
  }

  private Flyway flyway(Fixture fixture, String normalizedOwner) {
    return Flyway.configure().dataSource(fixture.url(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .placeholders(Map.of("legacy-owner-username",
            HexFormat.of().formatHex(normalizedOwner.getBytes(StandardCharsets.UTF_8))))
        .load();
  }

  private void insertUser(JdbcTemplate jdbc, UUID id, String normalizedUsername) {
    jdbc.update("""
        insert into users (id, username, normalized_username, password_hash, display_name,
          role, status, must_change_password, created_at, updated_at, version)
        values (?, ?, ?, 'hash', 'Legacy owner', 'ADMIN', 'ACTIVE', true, now(), now(), 0)
        """, id, normalizedUsername, normalizedUsername);
  }

  private void insertRoot(JdbcTemplate jdbc, String table, UUID owner) {
    String columns = switch (table) {
      case "resumes" -> "file_hash, original_filename, uploaded_at";
      case "knowledge_bases" -> "file_hash, name, original_filename, uploaded_at";
      case "interview_sessions" -> "session_id, created_at";
      case "voice_interview_sessions" -> "role_type";
      case "rag_chat_sessions" -> "title, created_at";
      case "interview_schedule" -> "company_name, position, interview_time, status";
      default -> throw new IllegalArgumentException(table);
    };
    String values = switch (table) {
      case "resumes" -> "'hash', 'legacy.pdf', now()";
      case "knowledge_bases" -> "'hash', 'Legacy', 'legacy.pdf', now()";
      case "interview_sessions" -> "'session-1', now()";
      case "voice_interview_sessions" -> "'Java'";
      case "rag_chat_sessions" -> "'Legacy', now()";
      case "interview_schedule" -> "'Company', 'Engineer', now(), 'PENDING'";
      default -> throw new IllegalArgumentException(table);
    };
    if (owner == null) {
      jdbc.update("insert into " + table + " (" + columns + ") values (" + values + ")");
    } else {
      jdbc.update("insert into " + table + " (" + columns + ", owner_user_id) values ("
          + values + ", ?)", owner);
    }
  }

  private record Fixture(String url, JdbcTemplate jdbc) {
  }
}
