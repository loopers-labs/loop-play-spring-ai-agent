#!/usr/bin/env bash
# cos_sim ranking of vector_store for a query, reproducing QuestionAnswerAdvisor search.
# usage: bash rank.sh "쿠폰 중복 사용되나요?"
set -euo pipefail
QUERY="${1:?usage: rank.sh <query>}"
VEC=$(curl -s -X POST http://localhost:11434/api/embed \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"qwen3-embedding:0.6b\",\"input\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$QUERY")}" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);v=d["embeddings"][0];print("["+",".join(repr(x) for x in v)+"]")')

docker exec -i baedal-pgvector psql -U baedal -d baedal -c "
SELECT round((1-(embedding <=> '${VEC}'::vector))::numeric,4) AS cos_sim,
       metadata->>'category' AS cat,
       length(content) AS chars,
       left(replace(content,E'\n',' '),58) AS preview
FROM vector_store
ORDER BY embedding <=> '${VEC}'::vector
LIMIT 10;"
