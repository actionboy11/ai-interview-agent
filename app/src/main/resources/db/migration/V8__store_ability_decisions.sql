ALTER TABLE ability_profile_proposals ADD COLUMN final_level varchar(20)
  CHECK (final_level IN ('WEAK', 'DEVELOPING', 'COMPETENT', 'PROFICIENT', 'STRONG'));
ALTER TABLE ability_profile_proposals ADD COLUMN final_score integer;
ALTER TABLE ability_profile_proposals ADD COLUMN final_summary text;
ALTER TABLE ability_profile_proposals DROP CONSTRAINT ck_ability_proposal_scores;
ALTER TABLE ability_profile_proposals ADD CONSTRAINT ck_ability_proposal_scores CHECK
  ((previous_score is null or previous_score between 0 and 100)
    and proposed_score between 0 and 100
    and (final_score is null or final_score between 0 and 100));
ALTER TABLE user_ability_profiles ADD COLUMN manually_edited boolean NOT NULL DEFAULT false;
ALTER TABLE user_ability_profiles ALTER COLUMN manually_edited DROP DEFAULT;
ALTER TABLE user_ability_profiles ADD COLUMN status_before_disable varchar(20)
  CHECK (status_before_disable IN ('ACTIVE', 'DISABLED', 'NEEDS_REVIEW'));
