# Air Pollution Tracker

[![CI](https://github.com/Yiftach128/air-pollution-tracker/actions/workflows/ci.yml/badge.svg)](https://github.com/Yiftach128/air-pollution-tracker/actions/workflows/ci.yml)
![Java 17](https://img.shields.io/badge/Java-17-blue)
![Kafka](https://img.shields.io/badge/Apache%20Kafka-3.9-black)
![Redis](https://img.shields.io/badge/Redis-8-red)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-336791)
![Grafana](https://img.shields.io/badge/Grafana-Loki-F46800)
![Kubernetes-ready](https://img.shields.io/badge/Kubernetes-Helm-326CE5)

Real-time air-quality monitoring built as **five Java microservices** around **Kafka**, **Redis** and **PostgreSQL**. It polls PurpleAir sensors, streams every reading through Kafka, keeps rolling averages per sensor, raises pollution alerts to Telegram, stores the history in PostgreSQL and serves a live UI dashboard. Every service ships as its own container image and runs unchanged on Kubernetes. Logs from all services are collected into **Grafana** through Loki.

<p align="center">
  <img src="public/screenshots/sensors-page.jpg" width="90%" alt="Overview: one card per sensor with its current readings">
</p>
<p align="center">
  <img src="public/screenshots/sensor-history.jpg" width="90%" alt="History: one sensor's averages against the threshold line">
</p>
<p align="left">
  <b>Telegram Pollution Alert:</b>
  <img src="public/screenshots/telegram-alert.jpg" width="25%" align="middle" alt="A PM2.5 alert as posted to the Telegram channel: sensor, cause, value and threshold">
</p>

## Architecture

<p align="center">
  <img src="public/architecture/architecture.jpg" width="75%" alt="Architecture diagram">
</p>

### Microservices

1. **pollution-data-collector** polls PurpleAir web-api and publishes each reading to the Kafka topic `pollution-data`. Running instances send heartbeats to Redis and split the sensors between them.
2. **pollution-data-analyzer** keeps rolling averages (24-h, 1-h, 10-min) per sensor and pollutant, persisted in Redis, and publishes them to `pollution-average`.
3. **pollution-alert-service** checks readings and averages against pre-defined health-risk thresholds, with per-series cooldowns in Redis, and posts alerts to a Telegram channel. The alert becomes words only at the edge.
4. **pollution-data-writer** stores every reading in PostgreSQL (the history) and in Redis (each sensor's current reading - for fast updates).
5. **pollution-api-service** joins Redis (live current readings) with PostgreSQL (history and aggregation) into a UI dashboard.

Kafka carries every message between services. Redis carries every piece of live state. PostgreSQL holds persistence.

| Service | Consumes | Produces | Redis                    | PostgreSQL | Replicas |
|---|---|---|--------------------------|---|---|
| `pollution-data-collector` | PurpleAir API | `pollution-data` | group membership         | – | any number, self-sharding |
| `pollution-data-analyzer` | `pollution-data` | `pollution-average` | rolling-average state    | – | 1, stateful by design |
| `pollution-alert-service` | both topics | Telegram | alert cooldowns          | – | 1, atomic cooldowns |
| `pollution-data-writer` | both topics | – | current readings (write) | history (write) | one per partition |
| `pollution-api-service` | – | HTTP `/api/*` | current readings (read)  | history, aggregates (read) | any number |

- **Scalable.** Each service gets its own replica count and resource limits according to its need.
- **Failures stay put.** Service being down never blocks other services, and Kafka retains what a restarted consumer missed.
- **Loose Coupling.** No service imports a Kafka, Redis or PostgreSQL class - everything talks to interfaces, so every service is unit-tested with hand-written fakes.


## Pub-sub with Kafka

| Topic | Message | Producer | Consumers |
|---|---|---|---|
| `pollution-data` | `PollutionData`: city, source, pollutant, value, timestamp | collector | analyzer, alert service, writer |
| `pollution-average` | `PollutionAverage`: one average per window | analyzer | alert service, writer |

- Messages are keyed by source, so one sensor's readings stay on one partition in timestamp order.
- **One consumer group per service**, so every service sees every message.
- Business code sees only `IPublisher<T>` and `ISubscriber<T>`.

## Redis

| Key | Owner | TTL | The key existing means |
|---|---|---|---|
| `collector:member:<id>` | collector | 30 s lease, renewed every 10 s | the instance is in the group |
| `analyzer:rolling-average-state:<source>:<pollutant>` | analyzer | 24 h after the last save | the series is live and restorable |
| `alert:last-sent:<source>:<pollutant>:<window>` | alert service | that series' cooldown | the series is cooling down |
| `latest-reading:<source>:<pollutant>` | writer writes, API service reads | 60 min | the sensor is currently reporting |

**Collector group membership:** Every instance renews its lease by heartbeat, lists the members, ranks them by id and takes the sensors of its rank. No leader, no coordination: when you start another collector instance, the sensors are reshared within a heartbeat; whn an instance dies others take over.

Business code never sees Redis, rather only the interface `IPollutionCache<T>`.

## PostgreSQL

Readings are kept long-term through `IPollutionRepository`, implemented with Hibernate ORM in `persistence-postgres`, the one module that knows the table layout.

- **Idempotent writes.** A unique constraint on `(source, pollutant, measured_at)` makes Kafka redeliveries free; sequence ids let inserts batch.
- **Aggregates computed by the database.** Per-pollutant summaries and epoch-aligned bucketed averages of any length, one query for 10-minute, hourly and daily charts.
- **Row classes never leave the module.** Domain records go in through `fromDomain` and out through `toDomain`; the schema is managed by Hibernate at startup.

## Dashboard

- **Overview** (`/`): one card per sensor with its current readings, refreshed every minute; values above the threshold are marked.
- **History** (`/history?source=…`): presets from 24 hours to a year or a custom day range; buckets follow the range (10-minute, hourly, daily), each pollutant a mean line with a min–max band and its threshold; state kept in the URL.

## Kubernetes

- **Scaling model per service (by design):** The collector by Redis membership (any replicas); the writer and API service by partition; the analyzer and alert service at one replica with `Recreate`.

### Deploy with Helm

`helm/air-pollution-tracker` deploys the whole system into one namespace: a `Deployment` per service and per store - Kafka (one KRaft broker), Redis and PostgreSQL - written as plain Kubernetes YAML. Kafka and PostgreSQL keep their data on `PVC`s that survive `helm uninstall` (Redis needs none - every key has a TTL). A `ConfigMap` hands every pod the stores' addresses and the health port, a `Secret` the password and API keys from a git-ignored values file, and a checksum annotation rolls the pods when only the configuration changed. Runs on a single-node k3s.

```sh
cp helm/air-pollution-tracker/values-secrets.example.yaml helm/air-pollution-tracker/values-secrets.yaml   # fill in
helm upgrade --install air-pollution helm/air-pollution-tracker -n air-pollution --create-namespace \
  -f helm/air-pollution-tracker/values-secrets.yaml
kubectl -n air-pollution get pods -w
```

- **Dashboard.** The API service is the one `Service` reachable from outside: `LoadBalancer` on port 8080 by default, so on k3s <http://localhost:8080/>; `NodePort` is the alternative (`apiService.service.type`).
- **Deploy a commit.** `--set image.tag=sha-<short commit>` changes the pod templates and rolls them out; with `latest` nothing changes on upgrade, so `kubectl rollout restart` instead.
- **Scale.** `kubectl -n air-pollution scale deployment/pollution-data-collector --replicas=3`; (`writer.replicas`, `apiService.replicas`)
- **Logs.** `kubectl -n air-pollution logs deployment/<service> -f`.

## Observability

`observability/` is a Docker Compose stack of **Loki**, **Grafana Alloy** and **Grafana**, provisioned with a "Pollution Services — Logs" dashboard: errors per service and live logs, filtered by `service` and `level`.

## Testing

Unit tests for every module. A test exercises one service alone, built the way its `Wiring` would build it, with hand-written fakes for Kafka, Redis, PostgreSQL, PurpleAir and Telegram. The fakes ship as test-jars next to the interfaces they fake.

## Project layout

```
pollution-common/          entities, IPublisher/ISubscriber (+ Kafka), JsonSupport, thresholds, health server, logging
persistence-common/        IPollutionCache, IPollutionRepository, ILatestReadingStore (no driver)
persistence-redis/         RedisPollutionCache (jedis)
persistence-postgres/      PostgresPollutionRepository (Hibernate ORM, HikariCP)
pollution-data-collector/  entities · api · registry · fetchers · persistence · membership
pollution-data-analyzer/   entities · analysis · persistence
pollution-alert-service/   entities · detection · persistence · senders/telegram
pollution-data-writer/
pollution-api-service/     entities · queries · web/jdk · static/
helm/                      Helm chart: the five services, Kafka, Redis and PostgreSQL
observability/             Loki + Alloy + Grafana
public/                    screenshots and the architecture diagram
Dockerfile                 one build for all five images
.env.example               every setting, with its default
.github/workflows/ci.yml   tests on every push, images on main
```

Every service has `config/Config.java` (values only) and `config/Wiring.java` (assembly only, the one place naming Kafka, Redis, PostgreSQL, Telegram or the HTTP server). Layers depend downward only; provider-specific code sits in a sub-package per provider.

## Run it locally

Java 17+, Maven, a PurpleAir read key, and Kafka, Redis and PostgreSQL on their default ports:

```sh
docker run -d --name kafka -p 9092:9092 apache/kafka:3.9.1
docker run -d --name redis -p 6379:6379 redis:8
docker run -d --name postgres -p 5432:5432 \
  -e POSTGRES_USER=pollution -e POSTGRES_PASSWORD=pollution -e POSTGRES_DB=pollution postgres:17

cp .env.example .env       # set PURPLEAIR_API_KEYS and POSTGRES_PASSWORD=pollution
mvn -DskipTests package    # or `mvn verify` to run the tests first
java -jar <service>/target/<service>.jar   # once per service, from any directory
```

- The dashboard is at <http://127.0.0.1:8080/>. Topics and the table are created on first use.
- `.env.example` documents every setting with its default; a variable set in the process environment wins over `.env`. Leave `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` empty to only log alerts.
- A second collector is just another `java -jar` of the same jar: the two find each other in Redis and reshare the sensors within a heartbeat. Give it its own `-Dapp.name` so its log file does not collide with the first one's.
- Logs go to the console and to `logs/<service>.json`, which the observability stack tails: `docker compose -f observability/docker-compose.yml up -d`, then Grafana at <http://localhost:3000/>.
- An image: `docker build --build-arg SERVICE=<service> -t <service> .`
