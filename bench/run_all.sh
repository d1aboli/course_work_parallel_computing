#!/usr/bin/env bash
# Runs the whole scalability study inside the running server container.
# Usage (Git Bash, from the project root, after `docker compose up -d`):  bash bench/run_all.sh
# Results: bench/results/*.csv|json  ->  python bench/plot.py  (graphs into report/figures)
set -euo pipefail
cd "$(dirname "$0")/.."
# Git Bash would rewrite container paths like /app/... into C:/Program Files/Git/app/...
export MSYS_NO_PATHCONV=1
# STEPS="4 5 6" bash bench/run_all.sh  re-runs only the selected parts
STEPS=${STEPS:-"1 2 3 4 5 6 7"}
step() { [[ " $STEPS " == *" $1 "* ]]; }
X="docker compose exec -T server"
RUNS=${RUNS:-3}
THREADS=${THREADS:-1,2,4,6,8,10,12,16}

if [[ "$STEPS" == "1 2 3 4 5 6 7" ]]; then
  $X sh -c 'rm -f /app/bench/results/*.csv /app/bench/results/*.json'
fi
$X sh -c 'nproc; cat /proc/cpuinfo | grep "model name" | head -1; free -m' | tee bench/results/env.txt
docker version --format 'docker {{.Server.Version}}' >> bench/results/env.txt || true

if step 1; then
echo "== 1. index build time vs threads (both implementations)"
$X bench index --threads "$THREADS" --runs "$RUNS" --impl partitioned,striped --partitions 64 --out index.csv
fi

if step 2; then
echo "== 2. effect of the partition count"
$X bench index --threads 1,4,8,12 --runs "$RUNS" --impl partitioned,striped --partitions 1,8,256 --out partitions.csv
fi

if step 3; then
$X sh -c "rm -f /app/bench/results/memory.csv"
echo "== 3. memory over repeated rebuilds"
$X bench memory --rebuilds 6 --threads 12 --no-gc --out memory.csv
$X bench memory --rebuilds 6 --threads 12 --out memory.csv
fi

if step 4; then
$X sh -c "rm -f /app/bench/results/throughput.csv"
$X bench index --threads 12 --runs 1 --out last_build.csv
echo "== 4. keyword search throughput vs clients"
$X bench throughput --clients 1,2,4,8,16,32,64 --duration 20 --warmup 5 --out throughput.csv
fi

if step 5; then
$X sh -c "rm -f /app/bench/results/throughput_llm.csv"
echo "== 5. search with LLM expansion (uncached, then cached)"
$X bench throughput --clients 1,2,4,8 --duration 30 --warmup 5 --expand --no-cache --out throughput_llm.csv
$X bench throughput --clients 1,8,32 --duration 20 --warmup 5 --expand --out throughput_llm.csv
fi

if step 6; then
echo "== 6. functional queries"
$X bench queries --file /app/bench/test_queries.txt --out queries.json
$X bench queries --file /app/bench/test_queries.txt --expand --out queries_expand.json
fi

if step 7; then
echo "== 7. first mapper version (String per token) for the before/after comparison"
$X bench index --threads "$THREADS" --runs "$RUNS" --impl partitioned --mapper hashmap --partitions 64 --out mapper_hashmap.csv
fi

echo "done: see bench/results"
