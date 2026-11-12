#!/usr/bin/env bash
# Runs the whole scalability study inside the running server container.
# Usage (Git Bash, from the project root, after `docker compose up -d`):  bash bench/run_all.sh
# Results: bench/results/*.csv|json  ->  python bench/plot.py  (graphs into report/figures)
set -euo pipefail
cd "$(dirname "$0")/.."
X="docker compose exec -T server"
RUNS=${RUNS:-3}
THREADS=${THREADS:-1,2,4,6,8,10,12,16}

$X sh -c 'rm -f /app/bench/results/*.csv /app/bench/results/*.json'
$X sh -c 'nproc; cat /proc/cpuinfo | grep "model name" | head -1; free -m' | tee bench/results/env.txt
docker version --format 'docker {{.Server.Version}}' >> bench/results/env.txt || true

echo "== 1. index build time vs threads (both implementations)"
$X bench index --threads "$THREADS" --runs "$RUNS" --impl partitioned,striped --partitions 64 --out index.csv

echo "== 2. effect of the partition count"
$X bench index --threads 1,4,8,12 --runs "$RUNS" --impl partitioned,striped --partitions 1,8,256 --out partitions.csv

echo "== 3. memory over repeated rebuilds"
$X bench memory --rebuilds 6 --threads 12 --no-gc --out memory.csv
$X bench memory --rebuilds 6 --threads 12 --out memory.csv

echo "== 4. keyword search throughput vs clients"
$X bench throughput --clients 1,2,4,8,16,32,64 --duration 20 --warmup 5 --out throughput.csv

echo "== 5. search with LLM expansion (uncached, then cached)"
$X bench throughput --clients 1,2,4,8 --duration 30 --warmup 5 --expand --no-cache --out throughput_llm.csv
$X bench throughput --clients 1,8,32 --duration 20 --warmup 5 --expand --out throughput_llm.csv

echo "== 6. functional queries"
$X bench queries --file /app/bench/test_queries.txt --out queries.json
$X bench queries --file /app/bench/test_queries.txt --expand --out queries_expand.json

echo "done: see bench/results"
