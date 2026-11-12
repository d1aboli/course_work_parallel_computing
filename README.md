# Паралельна пошукова система з LLM-розширенням запитів

Курсова робота з дисципліни «Паралельні обчислення» (КПІ ім. Ігоря Сікорського, ННІ ПСА, 2026).
Тема: «Дослідження та реалізація паралельних обчислень на прикладі побудови пошукової системи з інтеграцією LLM».

Система будує інвертований індекс над текстовим корпусом (≈1,7 ГБ, ≈17 тис. документів) паралельно,
власним пулом потоків, обслуговує одночасних клієнтів через REST API та WebSocket і розширює
запити синонімами за допомогою локальної LLM (Ollama).

| Що | Як реалізовано |
|---|---|
| Мова, залежності | Java 21, **лише JDK** — жодних сторонніх бібліотек |
| Пул потоків | `core/ThreadPool` — власний: черга під `ReentrantLock` + `Condition`, `Future`, graceful shutdown |
| Індекс | `core/PartitionedIndexBuilder` — хеш-партиції з неблокуючими (CAS) inbox-стеками й «combining»-власником партиції; `core/StripedIndexBuilder` — блокувальна альтернатива (lock striping) для порівняння |
| Публікація індексу | build-then-swap через `AtomicReference`, читання без блокувань |
| Ранжування | Okapi BM25, терми з LLM мають вагу 0,4 |
| HTTP / WebSocket | власний сервер на сокетах: HTTP/1.1 keep-alive, RFC 6455 (фрагментація, ping/pong, коди закриття) |
| LLM | Ollama `/api/generate`, LRU-кеш, об'єднання однакових запитів (single-flight), обмеження паралельних викликів, дедлайн з деградацією |
| Клієнти | CLI (`search "запит"`) і Web UI зі стрімінгом етапів пайплайну |

## Вимоги

- Docker Desktop (Windows/macOS) або Docker Engine + Compose v2 (Linux);
- Python 3.8+ — лише для одноразової підготовки датасету;
- ~6 ГБ вільної ОЗП для контейнерів (індекс + модель `qwen2.5:1.5b`).

## Датасет

Корпус — архів книжок Project Gutenberg `D1.7GB.zip` (2 809 файлів, 1,70 ГБ; видається на вводному
занятті) — у git не зберігається. Книжки ріжуться на документи ≈100 КБ по межах абзаців, бо вимога
завдання — не менше 10 000 документів; ліцензійні шапки Gutenberg вирізаються.

Архів кладеться в корінь проєкту (або шлях задається змінною `DATASET_ZIP` у `.env`). Під час
першого `docker compose up` одноразовий сервіс `dataset-init` розпаковує його в Docker-том `corpus`
(Linux-файлова система контейнерів) і записує `manifest.json` зі статистикою; наступні запуски
використовують готовий том. Том, а не bind mount з диска Windows, використано свідомо: читання
17 тис. дрібних файлів через міст Windows ↔ WSL2 у ~10 разів повільніше й приховало б масштабування.

```bash
docker compose run --rm dataset-init                     # (необов'язково) підготувати окремо
docker compose exec server cat /data/manifest.json       # статистика корпусу
docker volume rm pocw-search_corpus                      # перерізати корпус наново
python tools/prepare_dataset.py D1.7GB.zip data/docs     # те саме локально, без Docker
```

## Збирання й запуск

```bash
docker compose up --build -d     # dataset-init, ollama-init (одноразові), ollama, server
docker compose ps                # search-server має стати healthy
curl http://localhost:8080/api/health
```

Перший запуск завантажує модель (~1 ГБ) у том `ollama_data`; доки вона вантажиться, пошук працює,
а розширення запиту повертає порожній список (`expansion_source: "error"`). Web UI: <http://localhost:8080>.

Зупинка: `docker compose down` (сервер завершується штатно за SIGTERM).

### Без Docker (для розробки)

```bash
./build.sh test                                  # компіляція, app.jar і всі тести
DATA_DIR=data/docs java -jar build/app.jar       # потрібна локальна Ollama на :11434
```

## REST API

| Ендпоінт | Запит | Відповідь |
|---|---|---|
| `GET /api/health` | — | `{"status":"ok","ollama":true,"index_ready":true,"indexing":false}` |
| `POST /api/index` | `{"threads":4}` (+ необов'язкові `partitions`, `batch_size`, `impl`: `partitioned`\|`striped`) | `{"status":"ok","documents_indexed":17000,"time_ms":5100,...,"phases":{...}}` |
| `POST /api/search` | `{"query":"whale ship","mode":"keyword"}` (+ `expand`, `top_k`) | `{"results":[{"doc_id":"2701_0003","score":1.0,"snippet":"…"}],"time_ms":4}` |
| `GET /api/stats` | — | `{"documents":17000,"index_build_time_ms":5100,"threads_used":4,...}` |
| `WS /ws/search` | `{"query":"whale ship"}` | `query_expansion` → `keyword_search` → `done` |

Коди помилок: 400 — некоректний запит, 404/405 — невідомий шлях/метод, 409 — індекс ще не
побудовано або вже будується, 413/431 — завеликий запит, 426 — звичайний GET на `/ws/search`.

```bash
curl -X POST localhost:8080/api/index  -H 'Content-Type: application/json' -d '{"threads":8}'
curl -X POST localhost:8080/api/search -H 'Content-Type: application/json' \
     -d '{"query":"white whale captain","mode":"keyword","expand":true}'
curl localhost:8080/api/stats
```

## CLI

Клієнт працює всередині контейнера, тож на хості не потрібна Java:

```bash
./search "white whale captain"          # Git Bash / Linux / macOS
./search --expand "storm at sea"
./search --index 12                      # побудувати індекс на 12 потоках
./search --stats
search.cmd "white whale"                 # Windows cmd / PowerShell
```

## Дослідження масштабованості

```bash
bash bench/run_all.sh          # ~40–60 хв: час індексації 1–16 потоків × 2 реалізації × 3 прогони,
                               # вплив кількості партицій, пам'ять, throughput 1–64 клієнтів, LLM
python bench/plot.py           # графіки в report/figures
```

Сирі результати — `bench/results/*.csv`. Окремі виміри: `docker compose exec server bench index --threads 1,4,8 --runs 3`.

## Тести

`./build.sh test` (також запускаються під час `docker build` — збірка падає, якщо тест не пройшов):
пул потоків (паралельність, винятки, shutdown, 80 000 задач від 8 продюсерів), Treiber-стек під
навантаженням, порівняння паралельних побудов індексу з послідовним еталоном (обидві реалізації,
1–16 потоків, 1/3/64 партиції, режим максимальної конкуренції), BM25 і сніпети, розбір відповідей
LLM, кеш, дедлайн і обмеження паралельності на mock-Ollama, наскрізні тести REST і WebSocket
(keep-alive, 16 одночасних клієнтів під час перебудови індексу, фрагментовані кадри, коди закриття).

## Структура

```
src/main/java/ua/kpi/pocw/
  core/    ThreadPool, Indexer, IndexBuilder (+Partitioned, Striped), InvertedIndex, TreiberStack, Tokenizer
  net/     HttpServer, HttpRequest/Response, WebSocket
  api/     Router, SearchService, WsSearchHandler
  search/  Searcher (BM25), Snippets
  llm/     OllamaClient, QueryExpander
  cli/     SearchCli          bench/  Bench
src/test/java/...              власний тест-раннер і тести
web/                           Web UI (HTML/CSS/JS без фреймворків)
docs/uml/                      PlantUML-джерела та PNG
tools/prepare_dataset.py       підготовка корпусу
bench/                         сценарій дослідження, запити, results/
report/                        пояснювальна записка
```

## Стиль коду й коміти

Java — Google Java Style (2 пробіли, 100 символів, `.editorconfig`); повідомлення комітів —
[Conventional Commits](https://www.conventionalcommits.org/).
