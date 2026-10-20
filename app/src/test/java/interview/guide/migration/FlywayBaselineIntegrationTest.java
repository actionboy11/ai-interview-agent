package interview.guide.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import interview.guide.common.config.OwnershipMigrationConfiguration;
import interview.guide.modules.auth.service.UsernameNormalizer;
import jakarta.persistence.Entity;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class FlywayBaselineIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

  @Test
  @DisplayName("全新 PostgreSQL 数据库通过全部迁移创建完整业务结构和向量索引")
  void migratesCleanDatabase() throws Exception {
    JdbcTemplate jdbc = database("clean_install");
    Flyway flyway = flyway("clean_install");

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
    assertThat(jdbc.queryForList("select tablename from pg_tables where schemaname = ?",
        String.class, "public")).containsExactlyInAnyOrder(
        "flyway_schema_history", "resumes", "resume_analyses", "resume_analysis_dead_letters",
        "interview_sessions", "interview_answers", "interview_evaluation_dead_letters",
        "interview_schedule", "knowledge_bases", "knowledge_vectorization_dead_letters",
        "rag_chat_sessions", "rag_chat_messages", "rag_session_knowledge_bases",
        "llm_provider_config", "llm_global_setting", "voice_interview_sessions",
        "voice_interview_messages", "voice_interview_evaluations", "voice_evaluation_dead_letters",
        "agent_evaluation_runs", "agent_evaluation_cases", "agent_evaluation_stages",
        "ai_call_audits", "vector_store", "users", "registration_invites");
    assertThat(jdbc.queryForObject("select count(*) from pg_extension where extname = 'vector'",
        Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select format_type(a.atttypid, a.atttypmod)
        from pg_attribute a where a.attrelid = 'vector_store'::regclass
        and a.attname = 'embedding'
        """, String.class)).isEqualTo("vector(1024)");
    assertThat(jdbc.queryForObject("""
        select indexdef from pg_indexes where schemaname = 'public'
        and indexname = 'spring_ai_vector_index'
        """, String.class)).contains("USING hnsw", "vector_cosine_ops");
    // Schema validation catches drift in every mapped column, not just missing tables.
    hibernateSchema("clean_install", "validate");
    JdbcTemplate reference = database("hibernate_reference");
    hibernateSchema("hibernate_reference", "create");
    assertThat(jdbc.queryForList(BUSINESS_COLUMNS))
        .containsExactlyInAnyOrderElementsOf(reference.queryForList(BUSINESS_COLUMNS));
    assertThat(jdbc.queryForList(BUSINESS_CONSTRAINTS, String.class))
        .containsExactlyInAnyOrderElementsOf(
            reference.queryForList(BUSINESS_CONSTRAINTS, String.class));
    assertThat(jdbc.queryForList(BUSINESS_INDEXES))
        .containsExactlyInAnyOrderElementsOf(reference.queryForList(BUSINESS_INDEXES));
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }

  // Derive the reference from existing mappings, independently of the migration under test.
  private static final String BUSINESS_COLUMNS = """
      select table_name, column_name, data_type, character_maximum_length, is_nullable,
        is_identity, column_default, datetime_precision
      from information_schema.columns where table_schema = 'public'
      and table_name not in ('flyway_schema_history', 'vector_store')
      """;

  private static final String BUSINESS_CONSTRAINTS = """
      select t.relname || ':' || c.contype::text || ':' || pg_get_constraintdef(c.oid)
      from pg_constraint c join pg_class t on t.oid = c.conrelid
      join pg_namespace n on n.oid = t.relnamespace
      where n.nspname = 'public' and t.relname not in ('flyway_schema_history', 'vector_store')
      """;

  private static final String BUSINESS_INDEXES = """
      select indexname, indexdef from pg_indexes
      where schemaname = 'public' and indexname like 'idx_%'
      """;

  @Test
  @DisplayName("旧 V1 结构基线后先部署身份表并创建账号，再迁移 V3 且保留业务数据")
  void baselinesLegacySchemaWithoutLosingData() throws Exception {
    JdbcTemplate jdbc = database("legacy_install");
    // Current entity mappings now require owners. Freeze the historical V1 schema instead.
    Flyway.configure().dataSource(url("legacy_install"), POSTGRES.getUsername(), POSTGRES.getPassword())
        .target("1").load().migrate();
    jdbc.execute("drop table flyway_schema_history");
    var vectorStore = PgVectorStore.builder(jdbc, mock(EmbeddingModel.class))
        .dimensions(1024).initializeSchema(true).indexType(PgIndexType.HNSW)
        .distanceType(PgDistanceType.COSINE_DISTANCE).build();
    vectorStore.afterPropertiesSet();
    jdbc.execute("""
        insert into resumes (file_hash, original_filename, uploaded_at)
        values ('legacy-hash', 'legacy.pdf', timestamp '2026-10-19 12:00:00')
        """);
    jdbc.execute("insert into vector_store (content) values ('legacy vector content')");

    Flyway stageOne = Flyway.configure()
        .dataSource(url("legacy_install"), POSTGRES.getUsername(), POSTGRES.getPassword())
        .baselineOnMigrate(true).baselineVersion("1").target("2").load();
    assertThat(jdbc.queryForObject("select to_regclass('public.users')", String.class)).isNull();
    assertThat(stageOne.migrate().migrationsExecuted).isOne();
    jdbc.execute("""
        insert into users (id, username, normalized_username, password_hash, display_name,
          role, status, must_change_password, created_at, updated_at, version)
        values ('11111111-1111-1111-1111-111111111111', 'Legacy', 'legacy', 'hash', 'Legacy',
          'ADMIN', 'ACTIVE', true, now(), now(), 0)
        """);
    Flyway flyway = flyway("legacy_install");
    assertThat(flyway.migrate().migrationsExecuted).isOne();
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
    assertThat(jdbc.queryForObject("select type from flyway_schema_history where version = '1'",
        String.class)).isEqualTo("BASELINE");
    assertThat(jdbc.queryForList("select original_filename from resumes", String.class))
        .containsExactly("legacy.pdf");
    assertThat(jdbc.queryForList("select content from vector_store", String.class))
        .containsExactly("legacy vector content");
    hibernateSchema("legacy_install", "validate");
  }

  @Test
  @DisplayName("生产配置启动时先运行 Flyway 再校验实体并关闭向量自动建表")
  void productionProfileMigratesBeforeHibernateValidation() {
    // Production Compose initializes only the vector extension before the app starts.
    database("production_install").execute("create extension vector with schema public");
    try (var context = new SpringApplicationBuilder(MigrationApplication.class).profiles("prod")
        .run("--spring.main.web-application-type=none",
            "--spring.flyway.enabled=true",
            "--spring.datasource.url=" + url("production_install"),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword())) {
      assertThat(context.getBean(Flyway.class).info().current().getVersion().getVersion())
          .isEqualTo("3");
      assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
          .isEqualTo("validate");
      assertThat(context.getEnvironment().getProperty(
          "spring.ai.vectorstore.pgvector.initialize-schema", Boolean.class)).isFalse();
      assertThat(context.getBean(Flyway.class).getConfiguration().isBaselineOnMigrate()).isTrue();
      assertThat(context.getBean(Flyway.class).getConfiguration().getBaselineVersion().getVersion())
          .isEqualTo("1");
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EntityScan("interview.guide.modules")
  @Import({OwnershipMigrationConfiguration.class, UsernameNormalizer.class})
  @ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class,
      HibernateJpaAutoConfiguration.class})
  static class MigrationApplication {
  }

  private JdbcTemplate database(String name) {
    JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    admin.execute("create database " + name);
    return new JdbcTemplate(new DriverManagerDataSource(
        url(name), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  private Flyway flyway(String name) {
    return Flyway.configure().dataSource(url(name), POSTGRES.getUsername(), POSTGRES.getPassword())
        .baselineOnMigrate(true).baselineVersion("1")
        .placeholders(Map.of("legacy-owner-username", "6c6567616379")).load();
  }

  private String url(String name) {
    return POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
  }

  private void hibernateSchema(String schema, String action) throws Exception {
    var registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", url(schema))
        .applySetting("hibernate.connection.username", POSTGRES.getUsername())
        .applySetting("hibernate.connection.password", POSTGRES.getPassword())
        .applySetting("hibernate.hbm2ddl.auto", action)
        .applySetting("hibernate.hbm2ddl.halt_on_error", true)
        .applySetting("hibernate.physical_naming_strategy",
            PhysicalNamingStrategySnakeCaseImpl.class.getName())
        .build();
    try {
      var scanner = new ClassPathScanningCandidateComponentProvider(false);
      scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
      var sources = new MetadataSources(registry);
      for (var candidate : scanner.findCandidateComponents("interview.guide.modules")) {
        String className = candidate.getBeanClassName();
        sources.addAnnotatedClass(Class.forName(className));
      }
      try (var sessionFactory = sources.buildMetadata().buildSessionFactory()) {
        assertThat(sessionFactory.getMetamodel().getEntities()).hasSize(23);
      }
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }
}
