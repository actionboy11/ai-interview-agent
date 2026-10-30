package interview.guide.modules.interview.dynamic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@DisplayName("动态面试 V11 迁移在隔离 PostgreSQL 中的真实约束")
class DynamicMigrationPostgresTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
      .withDatabaseName("dynamic_test").withUsername("dynamic_test").withPassword("dynamic_test");

  private JdbcTemplate jdbc;
  private String url;
  private UUID owner;
  private Long sessionId;

  @BeforeEach
  void setUp() {
    String database = "dynamic_" + UUID.randomUUID().toString().replace("-", "");
    var admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(), POSTGRES.getPassword()));
    admin.execute("create database " + database);
    url = POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database);
    jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
        POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  @Test
  @DisplayName("旧 V2 数据迁移为固定模式且不改写题目、答案、报告或规划")
  void legacyV2HasFixedExecutionMode() {
    flyway("10").migrate();
    owner = user();
    sessionId = session(owner);
    jdbc.update("update interview_sessions set questions_json = ?, report_json = ? where id = ?",
        "[{\"question\":\"old\"}]", "{\"legacy\":true}", sessionId);
    jdbc.update("""
        insert into interview_question_plans
          (session_id, owner_user_id, plan_version, planning_mode, plan_json, created_at)
        values (?, ?, 2, 'ADAPTIVE', '{"planVersion":2}', now())
        """, sessionId, owner);
    jdbc.update("""
        insert into interview_answers (session_id, question_index, user_answer, answered_at, category_key)
        values (?, 0, '原始回答', now(), 'OTHER')
        """, sessionId);

    assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(2);
    assertThat(jdbc.queryForObject("select execution_mode from interview_sessions where id = ?",
        String.class, sessionId)).isEqualTo("LEGACY_FIXED");
    assertThat(jdbc.queryForObject("select report_json from interview_sessions where id = ?",
        String.class, sessionId)).isEqualTo("{\"legacy\":true}");
    assertThat(jdbc.queryForObject("select questions_json from interview_sessions where id = ?",
        String.class, sessionId)).isEqualTo("[{\"question\":\"old\"}]");
    assertThat(jdbc.queryForObject("select user_answer from interview_answers", String.class))
        .isEqualTo("原始回答");
    assertThat(jdbc.queryForObject("select submission_intent from interview_answers", String.class))
        .isNull();
    assertThat(jdbc.queryForObject("select request_id from interview_answers", UUID.class)).isNull();
    assertThat(jdbc.queryForObject("select plan_json from interview_question_plans", String.class))
        .isEqualTo("{\"planVersion\":2}");
    assertThat(jdbc.queryForObject("select count(*) from interview_dynamic_runtime", Integer.class))
        .isZero();
    assertThat(flyway(null).migrate().migrationsExecuted).isZero();
  }

  @Test
  @DisplayName("正式源答案与同归属请求唯一，直接跳过也不能跨会话复用请求")
  void duplicateSourceAnswerAndRequestAreRejected() {
    fixture();
    UUID requestId = UUID.randomUUID();
    Long source = answer(sessionId, owner, 0, requestId);
    decision(sessionId, owner, source, 0, requestId);
    assertThatThrownBy(() -> decision(sessionId, owner, source, 0, requestId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> answer(sessionId, owner, 1, requestId))
        .isInstanceOf(DataIntegrityViolationException.class);
    Long otherSession = session(owner);
    assertThatThrownBy(() -> answer(otherSession, owner, 0, requestId))
        .isInstanceOf(DataIntegrityViolationException.class);
    UUID otherOwner = user();
    assertThat(answer(session(otherOwner), otherOwner, 0, requestId)).isNotNull();
    // Every direct intent is a formal submission even with no text or decision.
    UUID directRequest = UUID.randomUUID();
    answer(otherSession, owner, 0, directRequest);
    jdbc.update("update interview_answers set submission_intent = 'SKIP', user_answer = '' "
        + "where session_id = ?", otherSession);
    assertThatThrownBy(() -> answer(session(owner), owner, 0, directRequest))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("运行、决策和评价任务拒绝跨归属与跨会话引用并级联清理")
  void crossOwnerRuntimeAndDeleteCascade() {
    fixture();
    UUID otherOwner = user();
    Long otherSession = session(otherOwner);
    assertThat(jdbc.queryForObject("select count(*) from pg_constraint "
        + "where conname = 'fk_dynamic_answer_session_owner'", Integer.class)).isOne();
    assertThatThrownBy(() -> answer(sessionId, otherOwner, 1, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_dynamic_answer_session_owner");
    assertThatThrownBy(() -> runtime(otherSession, owner))
        .isInstanceOf(DataIntegrityViolationException.class);
    runtime(otherSession, otherOwner);
    UUID request = UUID.randomUUID();
    Long source = answer(sessionId, owner, 0, request);
    assertThatThrownBy(() -> decision(sessionId, otherOwner, source, 0, request))
        .isInstanceOf(DataIntegrityViolationException.class);
    Long otherAnswer = answer(otherSession, otherOwner, 0, UUID.randomUUID());
    assertThatThrownBy(() -> decision(sessionId, owner, otherAnswer, 0, request))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> decision(sessionId, owner, source, 1, request))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> decision(sessionId, owner, source, 0, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> outbox(sessionId, otherOwner, 1, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
    decision(sessionId, owner, source, 0, request);
    outbox(sessionId, owner, 1, UUID.randomUUID());

    // Existing session deletion first removes its legacy answer children.
    jdbc.update("delete from interview_answers where session_id = ?", sessionId);
    assertThat(count("interview_follow_up_decisions")).isZero();
    jdbc.update("delete from interview_sessions where id = ?", sessionId);
    assertThat(count("interview_dynamic_runtime")).isZero();
    assertThat(count("interview_dynamic_evaluation_outbox")).isZero();
  }

  @Test
  @DisplayName("迁移后旧答案仍允许所有提交元数据为空且不会生成动态任务")
  void legacyAnswerStillAcceptsNullSubmissionMetadata() {
    fixture();
    jdbc.update("insert into interview_answers "
        + "(session_id, question_index, category_key, answered_at) values (?, 0, 'OTHER', now())",
        sessionId);
    assertThat(jdbc.queryForObject("select request_id from interview_answers where session_id = ?",
        UUID.class, sessionId)).isNull();
    assertThat(count("interview_follow_up_decisions")).isZero();
  }

  @Test
  @DisplayName("冻结评价版本与稳定消息唯一，预算、索引、deadline与任务状态受 CHECK 保护")
  void uniquenessAndChecksAreEnforced() {
    fixture();
    assertThatThrownBy(() -> runtime(sessionId, owner))
        .isInstanceOf(DataIntegrityViolationException.class);
    UUID message = UUID.randomUUID();
    outbox(sessionId, owner, 4, message);
    assertThatThrownBy(() -> outbox(sessionId, owner, 4, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> outbox(sessionId, owner, 5, message))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> outbox(sessionId, owner, -1, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);
    for (String change : new String[] {"used_follow_ups = 2", "group_used_follow_ups = 3",
        "current_main_ordinal = -1", "current_question_index = -1", "total_follow_up_budget = 2",
        "max_follow_ups_per_group = 3", "decision_timeout_seconds = 0", "phase = 'UNKNOWN'"}) {
      assertThatThrownBy(() -> jdbc.update("update interview_dynamic_runtime set " + change
          + " where session_id = ?", sessionId)).isInstanceOf(DataIntegrityViolationException.class);
    }
    UUID request = UUID.randomUUID();
    Long source = answer(sessionId, owner, 0, request);
    decision(sessionId, owner, source, 0, request);
    for (String change : new String[] {"deadline = created_at", "delivery_attempts = -1",
        "status = 'UNKNOWN'", "delivery_status = 'UNKNOWN'", "applied_runtime_version = -1"}) {
      assertThatThrownBy(() -> jdbc.update("update interview_follow_up_decisions set " + change
          + " where session_id = ?", sessionId)).isInstanceOf(DataIntegrityViolationException.class);
    }
    assertThatThrownBy(() -> jdbc.update("update interview_answers set submission_owner_id = null "
        + "where id = ?", source)).isInstanceOf(DataIntegrityViolationException.class);
  }

  private void fixture() {
    flyway(null).migrate();
    owner = user();
    sessionId = session(owner);
    runtime(sessionId, owner);
  }

  @Test
  @DisplayName("V12 支持来源强制同归属会话类别、唯一性及证据删除级联")
  void supportingSourcesEnforceProvenanceAndDeletion() {
    fixture();
    assertThat(jdbc.queryForList("select tablename from pg_tables where schemaname = 'public'",
        String.class)).contains("user_ability_evidence_sources");
    Long anchor = answer(sessionId, owner, 0, UUID.randomUUID());
    Long support = answer(sessionId, owner, 1, UUID.randomUUID());
    Long wrongCategory = answer(sessionId, owner, 2, UUID.randomUUID());
    jdbc.update("update interview_answers set category_key = 'JAVA' where id = ?", wrongCategory);
    Long otherSession = session(owner);
    Long wrongSession = answer(otherSession, owner, 0, UUID.randomUUID());
    UUID otherOwner = user();
    Long foreignAnswer = answer(session(otherOwner), otherOwner, 0, UUID.randomUUID());
    Long proposal = jdbc.queryForObject("""
        insert into ability_profile_proposals (user_id, interview_session_id, skill_id,
          category_key, proposed_level, proposed_score, status, created_at, version)
        values (?, ?, 'java-backend', 'OTHER', 'COMPETENT', 70, 'PROPOSED', now(), 0)
        returning id
        """, Long.class, owner, sessionId);
    Long evidence = jdbc.queryForObject("""
        insert into user_ability_evidence (user_id, interview_session_id, interview_answer_id,
          proposal_id, skill_id, category_key, question_score, occurred_at)
        values (?, ?, ?, ?, 'java-backend', 'OTHER', 70, now()) returning id
        """, Long.class, owner, sessionId, anchor, proposal);
    String source = """
        insert into user_ability_evidence_sources
          (evidence_id, answer_id, user_id, interview_session_id, category_key)
        values (?, ?, ?, ?, ?)
        """;
    jdbc.update(source, evidence, anchor, owner, sessionId, "OTHER");
    jdbc.update(source, evidence, support, owner, sessionId, "OTHER");
    assertThatThrownBy(() -> jdbc.update(source, evidence, support, owner, sessionId, "OTHER"))
        .hasMessageContaining("uk_ability_evidence_source");
    for (Long invalid : new Long[] {wrongCategory, wrongSession, foreignAnswer}) {
      assertThatThrownBy(() -> jdbc.update(source, evidence, invalid, owner, sessionId, "OTHER"))
          .hasMessageContaining("fk_ability_source_answer_scope");
    }
    assertThatThrownBy(() -> jdbc.update(source, evidence, wrongSession, owner, otherSession, "OTHER"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(source, evidence, foreignAnswer, otherOwner,
        sessionId, "OTHER")).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("delete from interview_answers where id = ?", support))
        .hasMessageContaining("fk_ability_source_answer_scope");
    assertThatThrownBy(() -> jdbc.update("delete from interview_sessions where id = ?", sessionId))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update("delete from user_ability_evidence where id = ?", evidence);
    assertThat(jdbc.queryForObject("select count(*) from user_ability_evidence_sources "
        + "where evidence_id = ?", Integer.class, evidence)).isZero();
    jdbc.update("delete from ability_profile_proposals where id = ?", proposal);
    jdbc.update("delete from interview_answers where session_id = ?", sessionId);
    jdbc.update("delete from interview_sessions where id = ?", sessionId);
  }

  private Flyway flyway(String target) {
    var config = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
        .placeholders(Map.of("legacy-owner-username", ""));
    if (target != null) {
      config.target(target);
    }
    return config.load();
  }

  private UUID user() {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into users (id, username, normalized_username, password_hash, display_name,
          role, status, must_change_password, created_at, updated_at, version)
        values (?, ?, ?, 'test-only-hash', '测试用户', 'USER', 'ACTIVE', false, now(), now(), 0)
        """, id, id.toString(), id.toString());
    return id;
  }

  private Long session(UUID user) {
    return jdbc.queryForObject("""
        insert into interview_sessions (session_id, owner_user_id, created_at)
        values (?, ?, now()) returning id
        """, Long.class, UUID.randomUUID().toString(), user);
  }

  private void runtime(Long session, UUID user) {
    jdbc.update("""
        insert into interview_dynamic_runtime (session_id, owner_user_id, version,
          execution_version, phase, main_questions_json, policy_json, planned_main_question_count,
          max_follow_ups_per_group, total_follow_up_budget, decision_timeout_seconds,
          current_slot_id, current_main_ordinal, current_question_index, group_used_follow_ups,
          used_follow_ups, completed_main_count, group_end_reasons_json)
        values (?, ?, 0, 1, 'WAITING_ANSWER', '[]', '{"schemaVersion":1}', 1, 2, 1, 30,
          'slot-1', 0, 0, 0, 0, 0, '{"schemaVersion":1,"groups":{}}')
        """, session, user);
  }

  private Long answer(Long session, UUID user, int index, UUID request) {
    return jdbc.queryForObject("""
        insert into interview_answers (session_id, question_index, answered_at, category_key,
          submission_intent, request_id, request_fingerprint, submission_owner_id)
        values (?, ?, now(), 'OTHER', 'ANSWER', ?, ?, ?) returning id
        """, Long.class, session, index, request, "a".repeat(64), user);
  }

  private void decision(Long session, UUID user, Long source, int index, UUID request) {
    jdbc.update("""
        insert into interview_follow_up_decisions (id, session_id, owner_user_id,
          source_answer_id, source_question_index, slot_id, request_id, request_fingerprint,
          context_json, deadline, created_at, expected_runtime_version, status, delivery_status,
          next_delivery_at, delivery_attempts, version)
        values (?, ?, ?, ?, ?, 'slot-1', ?, ?, '{"schemaVersion":1}',
          timestamp '2026-10-29 08:00:30+00', timestamp '2026-10-29 08:00:00+00',
          0, 'PENDING', 'PENDING', now(), 0, 0)
        """, UUID.randomUUID(), session, user, source, index, request, "a".repeat(64));
  }

  private void outbox(Long session, UUID user, long version, UUID messageId) {
    jdbc.update("""
        insert into interview_dynamic_evaluation_outbox (id, session_id, owner_user_id,
          frozen_version, message_id, frozen_snapshot_json, delivery_status, created_at,
          next_delivery_at, delivery_attempts, version)
        values (?, ?, ?, ?, ?, '{"schemaVersion":1}', 'PENDING', now(), now(), 0, 0)
        """, UUID.randomUUID(), session, user, version, messageId);
  }

  private int count(String table) {
    return jdbc.queryForObject("select count(*) from " + table + " where session_id = ?",
        Integer.class, sessionId);
  }
}
