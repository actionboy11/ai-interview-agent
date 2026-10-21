-- Keep the legacy display text while introducing a stable machine key.
ALTER TABLE interview_answers RENAME COLUMN category TO category_label;
ALTER TABLE interview_answers ADD COLUMN category_key varchar(255);

-- Only unambiguous preset keys are recovered from old display values.
-- Custom or localized labels cannot safely be reverse-mapped and become OTHER.
UPDATE interview_answers
SET category_key = CASE
  WHEN upper(trim(category_label)) IN (
    'AGENT_BASIS', 'API_TESTING', 'ARRAY_LINKEDLIST', 'AUTOMATION', 'BROWSER',
    'CACHE', 'CONTEXT_ENGINEERING', 'CSS', 'DATABASE', 'DB_DESIGN', 'DEPLOY',
    'DESIGN', 'DESIGN_PATTERN', 'DISTRIBUTED', 'DJANGO_FLASK', 'DP',
    'ENGINEERING', 'GREEDY_BACKTRACK', 'HASH_HEAP_QUEUE', 'HIGH_AVAILABILITY',
    'JAVA', 'JAVASCRIPT', 'LLM_CALLING', 'MCP_PROTOCOL', 'MQ', 'MULTI_AGENT',
    'MYSQL', 'NET_OS', 'PERFORMANCE', 'PERFORMANCE_SECURITY', 'PROJECT',
    'PYTHON_BASIC', 'RAG', 'REACT_VUE', 'REDIS', 'SPRING',
    'SYSTEM_DESIGN_SCENARIO', 'TESTING_BASIC', 'TEST_CASE_DESIGN',
    'TREE_GRAPH', 'WEB_MOBILE_TESTING'
  ) THEN upper(trim(category_label))
  ELSE 'OTHER'
END;

ALTER TABLE interview_answers ALTER COLUMN category_key SET NOT NULL;
