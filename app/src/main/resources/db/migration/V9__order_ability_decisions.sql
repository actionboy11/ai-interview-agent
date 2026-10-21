ALTER TABLE ability_profile_proposals ADD COLUMN decision_order bigint;

-- Existing accepted proposals are ordered by their recorded decision time, with ID as a tie-breaker.
WITH ranked AS (
  SELECT id, row_number() OVER (
    PARTITION BY user_id, skill_id, category_key ORDER BY decided_at, id
  ) AS sequence
  FROM ability_profile_proposals
  WHERE status IN ('ACCEPTED', 'ACCEPTED_WITH_EDIT')
)
UPDATE ability_profile_proposals proposal
SET decision_order = ranked.sequence
FROM ranked WHERE proposal.id = ranked.id;
