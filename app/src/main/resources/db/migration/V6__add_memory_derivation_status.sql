ALTER TABLE interview_sessions
  ADD COLUMN memory_derivation_status varchar(20)
    CHECK (memory_derivation_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
  ADD COLUMN memory_derivation_error varchar(500),
  ADD COLUMN memory_derivation_message_id varchar(36);
