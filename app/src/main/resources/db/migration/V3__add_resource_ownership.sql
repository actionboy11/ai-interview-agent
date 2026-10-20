-- All six root tables are locked until commit, so no writer can insert an unassigned row
-- between the legacy-data check, backfill and NOT NULL validation.
LOCK TABLE resumes, interview_sessions, voice_interview_sessions,
  knowledge_bases, rag_chat_sessions, interview_schedule IN ACCESS EXCLUSIVE MODE;

ALTER TABLE resumes ADD COLUMN owner_user_id uuid;
ALTER TABLE interview_sessions ADD COLUMN owner_user_id uuid;
ALTER TABLE voice_interview_sessions ADD COLUMN owner_user_id uuid;
ALTER TABLE knowledge_bases ADD COLUMN owner_user_id uuid;
ALTER TABLE rag_chat_sessions ADD COLUMN owner_user_id uuid;
ALTER TABLE interview_schedule ADD COLUMN owner_user_id uuid;

DO $ownership$
DECLARE
  legacy_owner uuid;
  owner_count bigint;
  -- The application customizer supplies normalized UTF-8 HEX ONLY, never raw username text.
  configured_username text := convert_from(decode('${legacy-owner-username}', 'hex'), 'UTF8');
BEGIN
  IF EXISTS (SELECT 1 FROM resumes)
      OR EXISTS (SELECT 1 FROM interview_sessions)
      OR EXISTS (SELECT 1 FROM voice_interview_sessions)
      OR EXISTS (SELECT 1 FROM knowledge_bases)
      OR EXISTS (SELECT 1 FROM rag_chat_sessions)
      OR EXISTS (SELECT 1 FROM interview_schedule) THEN
    -- Prevent account deletion/change between resolution and FK creation.
    LOCK TABLE users IN SHARE MODE;
    SELECT count(*) INTO owner_count FROM users
      WHERE normalized_username = configured_username AND configured_username <> '';
    IF owner_count <> 1 THEN
      RAISE EXCEPTION 'Ownership migration requires exactly one existing legacy owner; bootstrap the configured account before V3';
    END IF;
    SELECT id INTO STRICT legacy_owner FROM users
      WHERE normalized_username = configured_username;

    UPDATE resumes SET owner_user_id = legacy_owner;
    UPDATE interview_sessions SET owner_user_id = legacy_owner;
    UPDATE voice_interview_sessions SET owner_user_id = legacy_owner;
    UPDATE knowledge_bases SET owner_user_id = legacy_owner;
    UPDATE rag_chat_sessions SET owner_user_id = legacy_owner;
    UPDATE interview_schedule SET owner_user_id = legacy_owner;
  END IF;
END
$ownership$;

ALTER TABLE resumes ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE resumes ADD CONSTRAINT fk_resume_owner FOREIGN KEY (owner_user_id) REFERENCES users (id);
ALTER TABLE interview_sessions ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE interview_sessions ADD CONSTRAINT fk_interview_owner FOREIGN KEY (owner_user_id) REFERENCES users (id);
ALTER TABLE voice_interview_sessions ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE voice_interview_sessions ADD CONSTRAINT fk_voice_owner FOREIGN KEY (owner_user_id) REFERENCES users (id);
ALTER TABLE knowledge_bases ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE knowledge_bases ADD CONSTRAINT fk_kb_owner FOREIGN KEY (owner_user_id) REFERENCES users (id);
ALTER TABLE rag_chat_sessions ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE rag_chat_sessions ADD CONSTRAINT fk_rag_owner FOREIGN KEY (owner_user_id) REFERENCES users (id);
ALTER TABLE interview_schedule ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE interview_schedule ADD CONSTRAINT fk_schedule_owner FOREIGN KEY (owner_user_id) REFERENCES users (id);

-- Hibernate legacy installations can have generated constraint names and/or named unique
-- indexes. Resolve by the single file_hash column, not by an assumed generated name.
DO $hash_uniqueness$
DECLARE
  hash_table text;
  constraint_name text;
  index_name text;
  hash_attribute smallint;
BEGIN
  FOREACH hash_table IN ARRAY ARRAY['resumes', 'knowledge_bases'] LOOP
    SELECT attnum INTO STRICT hash_attribute FROM pg_attribute
      WHERE attrelid = hash_table::regclass AND attname = 'file_hash' AND NOT attisdropped;
    FOR constraint_name IN
      SELECT conname FROM pg_constraint
      WHERE conrelid = hash_table::regclass AND contype = 'u'
        AND conkey = ARRAY[hash_attribute]::smallint[] ORDER BY conname
    LOOP
      EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', hash_table, constraint_name);
    END LOOP;
    FOR index_name IN
      SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
      WHERE i.indrelid = hash_table::regclass AND i.indisunique AND NOT i.indisprimary
        AND i.indnkeyatts = 1 AND i.indkey[0] = hash_attribute
      ORDER BY c.relname
    LOOP
      EXECUTE format('DROP INDEX %I', index_name);
    END LOOP;
  END LOOP;
END
$hash_uniqueness$;

ALTER TABLE resumes ADD CONSTRAINT uk_resume_owner_hash UNIQUE (owner_user_id, file_hash);
ALTER TABLE knowledge_bases ADD CONSTRAINT uk_kb_owner_hash UNIQUE (owner_user_id, file_hash);

CREATE INDEX idx_resume_owner_uploaded ON resumes (owner_user_id, uploaded_at);
CREATE INDEX idx_interview_owner_created ON interview_sessions (owner_user_id, created_at);
CREATE INDEX idx_interview_owner_session ON interview_sessions (owner_user_id, session_id);
CREATE INDEX idx_voice_owner_updated ON voice_interview_sessions (owner_user_id, updated_at);
CREATE INDEX idx_kb_owner_uploaded ON knowledge_bases (owner_user_id, uploaded_at);
CREATE INDEX idx_rag_owner_pinned_updated ON rag_chat_sessions (owner_user_id, is_pinned, updated_at);
CREATE INDEX idx_schedule_owner_time ON interview_schedule (owner_user_id, interview_time);
