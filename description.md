# WeCare Repository Detailed Description

## 1. What this repository does

This repository implements a real-time eldercare telemetry proof-of-concept (PoC).

It simulates patient vital-sign and movement data, streams telemetry events through Kafka, evaluates safety/anomaly rules in Apache Flink, routes alerts to nurse-facing RabbitMQ queues, optionally stores latest patient alert state in Redis, and exposes a web dashboard through Spring Boot so clinicians can monitor active and pinned patients.

In short: this is an event-driven monitoring pipeline for eldercare, with a browser-facing operational dashboard.

## 2. High-level architecture

Runtime data path:

1. Telemetry simulation: `scripts/patient_telemetry_simulator.py`
2. Event bus: Kafka topic `elder-vitals-inbound`
3. Stream processing and rules: `src/main/java/uk/ac/ed/inf/wecare/flink/EldercareAlertPipeline.java`
4. Alert routing bus: RabbitMQ queues `nurse-urgent-alerts` and `nurse-warnings`
5. Nurse consumers: `scripts/nurse_station_consumer.py` and `scripts/nurse_warning_consumer.py`
6. Optional cache update: Redis keys `patient_<id>_status` and set `pinned_patients`
7. API + UI: Spring Boot controllers + `src/main/resources/static/dashboard.html`

Support infrastructure is defined in `docker-compose.yml`: Kafka, RabbitMQ, Redis, Postgres, LocalStack.

## 3. Services and how each operates

### 3.1 Spring Boot application service

Main entry point:
- `src/main/java/uk/ac/ed/inf/wecare/WeCareApplication.java`

Role:
- Starts the Java web application context.
- Creates infrastructure beans from config classes on startup.
- Serves static web assets from `src/main/resources/static/`.

Main runtime APIs:
- `src/main/java/uk/ac/ed/inf/wecare/controller/WeCareController.java`
  - `GET /` redirects to `/dashboard.html`
  - `GET /healthz` returns `ok`
- `src/main/java/uk/ac/ed/inf/wecare/controller/PatientStatusController.java`
  - `GET /api/patients/status`
  - `POST /api/patients/{patientId}/pin`
  - `DELETE /api/patients/{patientId}/pin`

Business logic:
- `src/main/java/uk/ac/ed/inf/wecare/service/PatientStatusService.java`
  - Reads Redis hash keys matching `patient_*_status`.
  - Reads pinned IDs from Redis set `pinned_patients`.
  - Joins Redis status with static patient profile metadata (name, home room, baseline vitals, dementia flag).
  - Returns `PatientStatusView` list sorted with pinned patients first.
  - Supports pin/unpin operations by set add/remove.

Data model returned by API:
- `src/main/java/uk/ac/ed/inf/wecare/model/PatientStatusView.java`
  - Includes patient identity, current status/rule/message/location, baseline values, timestamp, and pinned/active flags.

### 3.2 Flink stream-processing service

Entry point:
- `src/main/java/uk/ac/ed/inf/wecare/flink/EldercareAlertPipeline.java`

Role:
- Consumes telemetry records from Kafka.
- Applies rule-based anomaly detection.
- Emits structured alert JSON messages.
- Routes URGENT vs WARNING alerts to separate RabbitMQ queues.

Detailed operation:
- Kafka source reads topic (default `elder-vitals-inbound`) from bootstrap server (default `localhost:9092`).
- `TelemetryParser` parses incoming JSON fields into typed `PatientTelemetry`.
- Rules implemented:
  - Rule A: oxygen `< 90` -> `VITALS_OXYGEN_LOW` (URGENT)
  - Rule B: location zone `Exit Gate` -> `GEOFENCE_EXIT_GATE` (URGENT)
  - Rule C: movement `Fall Detected` -> `FALL_DETECTED` (URGENT)
  - Rule D: movement `Recovered` -> `PATIENT_RECOVERED` (treated as URGENT path for guaranteed handling)
  - Rule E: 10-second tumbling processing-time window average heart rate `< 50` or `> 120` -> `HEART_RATE_TREND_10S`
    - Escalates to URGENT if average `< 45` or `> 130`, else WARNING
- Alert JSON includes rule name, severity, patient fields, timestamps, and optional trend/window metrics.
- Rabbit sink (`RabbitMqAlertSink`) publishes persistent messages directly to configured queues.

### 3.3 Python telemetry simulator service

File:
- `scripts/patient_telemetry_simulator.py`

Role:
- Produces continuous synthetic telemetry for predefined patients.
- Allows interactive fault injection to test alerting pipeline.

Detailed operation:
- Defines 8 patients with baseline heart rate, oxygen, room, and dementia flag.
- Every cycle, builds payload with:
  - Gaussian-style heart rate and oxygen around baseline.
  - Home-room biased location.
  - Movement state (`Active`/`Resting`).
- Publishes JSON to Kafka topic with `patient_id` as message key.
- Interactive commands:
  - `crash <id>` forces low oxygen and low HR profile.
  - `wander <id>` forces `Exit Gate` location.
  - `fall <id>` forces `Fall Detected` movement.
  - `recover <id>` emits `Recovered` behavior and clears forced anomalies.

### 3.4 Python nurse consumer services

Files:
- `scripts/nurse_station_consumer.py`
- `scripts/nurse_warning_consumer.py`

Role:
- Consume alerts from RabbitMQ and display nurse-readable output.
- Optionally update Redis so the dashboard has current alert state.

Detailed operation (`nurse_station_consumer.py`):
- Connects to RabbitMQ and consumes from queue (`nurse-urgent-alerts` default).
- Acknowledges each message after processing.
- Formats and prints alert details to console.
- Optional Redis mode (`--redis-enabled`):
  - Writes hash `patient_<id>_status` with status/rule/message/location/timestamp.
  - If rule is `PATIENT_RECOVERED`, deletes `patient_<id>_status` to clear active alert.

`nurse_warning_consumer.py` is a convenience launcher that reuses the same consumer logic but starts it in warning mode on queue `nurse-warnings`.

### 3.5 Browser dashboard service

File:
- `src/main/resources/static/dashboard.html`

Role:
- Renders a live patient status board from Spring API data.
- Allows pin and unpin actions.

Detailed operation:
- Calls `GET /api/patients/status` every 3 seconds.
- Displays cards including current status, rule, location, dementia flag, and baseline vitals.
- Pin/unpin buttons call:
  - `POST /api/patients/{id}/pin`
  - `DELETE /api/patients/{id}/pin`
- Auto-refresh can be paused/resumed from UI controls.

## 4. Communication technologies between services

This repository is built around multiple communication mechanisms.

### 4.1 Kafka (telemetry transport)

Used between:
- Telemetry simulator -> Flink pipeline

Software/libraries:
- Broker container: `apache/kafka-native` (Docker)
- Python producer client: `kafka-python`
- Java ecosystem libs: Spring Kafka (`spring-kafka`) and Flink Kafka connector (`flink-connector-kafka`)

Protocol/data:
- Kafka protocol over TCP (`localhost:9092` externally)
- JSON payload per patient telemetry event

Configured names:
- Topic: `elder-vitals-inbound`

### 4.2 RabbitMQ (alert routing)

Used between:
- Flink pipeline -> Nurse consumers

Software/libraries:
- Broker container: `rabbitmq:4.0-management`
- Java sink client: Rabbit Java client (`com.rabbitmq.client`) via Flink custom sink
- Python consumer client: `pika`
- Spring declaration support: `spring-boot-starter-amqp` for queue declaration beans

Protocol/data:
- AMQP 0-9-1 over TCP (`localhost:5672`)
- Persistent JSON alert messages

Configured queues:
- `nurse-urgent-alerts`
- `nurse-warnings`
- `assignment_queue` (declared for future use)

### 4.3 Redis (patient status cache + pinned set)

Used between:
- Nurse consumers (writer) -> Spring API/dashboard path (reader/writer for pinned set)

Software/libraries:
- Redis container: `redis/redis-stack`
- Python client: `redis`
- Java client abstraction: Spring Data Redis (`spring-boot-starter-data-redis`)

Protocol/data:
- Redis protocol over TCP (`localhost:6379`)
- Hashes: `patient_<id>_status`
- Set: `pinned_patients`

### 4.4 HTTP/REST (frontend interaction)

Used between:
- Browser dashboard <-> Spring Boot controllers

Software/libraries:
- Spring MVC web stack: `spring-boot-starter-webmvc`
- Browser API calls: JavaScript `fetch`

Protocol/data:
- HTTP on `localhost:8080`
- JSON request/response for patient status and pin operations

### 4.5 Optional integrations present but disabled by default

Configured in `src/main/resources/application.properties`:
- `wecare.integrations.postgres.enabled=false`
- `wecare.integrations.dynamodb.enabled=false`
- `wecare.integrations.s3.enabled=false`

Available configuration classes:
- `src/main/java/uk/ac/ed/inf/wecare/config/PostgresConfig.java` (HikariCP DataSource)
- `src/main/java/uk/ac/ed/inf/wecare/config/DynamoDBConfig.java` (AWS SDK DynamoDbClient)
- `src/main/java/uk/ac/ed/inf/wecare/config/S3Config.java` (AWS SDK S3Client)

These beans are conditional and only activate when explicitly enabled.

## 5. Infrastructure and ports

From `docker-compose.yml`:

- Kafka: `9092` (host) and `9093` (Docker-internal listener)
- RabbitMQ: `5672` (AMQP), `15672` (management UI)
- Redis Stack: `6379` (Redis), `8001` (RedisInsight)
- Postgres: `5432`
- LocalStack: `4566` (for S3/Dynamo emulation)

## 6. End-to-end runtime sequence

Typical run:

1. Start infrastructure containers.
2. Start Spring Boot app (creates Kafka topic and Rabbit queues via config beans).
3. Start Flink pipeline process.
4. Start telemetry simulator (publishes events to Kafka).
5. Flink consumes telemetry and emits rule-based alerts to Rabbit queues.
6. Nurse consumers read queue messages and optionally write latest status into Redis.
7. Dashboard fetches status API and shows live alert/pinned patient cards.

Result:
- Real-time anomaly visibility for eldercare patients, with a separation of urgent vs warning alert channels, and a dashboard backed by Redis state.

## 7. Repository software stack summary

Primary languages:
- Java 17 (Spring Boot, Flink)
- Python 3 (simulator and consumers)
- HTML/CSS/JavaScript (dashboard)

Key frameworks/libraries:
- Spring Boot 4.0.2 (web, AMQP, Redis, Kafka)
- Apache Flink 1.19.2
- Flink Kafka connector 3.2.0-1.19
- AWS SDK v2 (DynamoDB, S3 clients available)
- HikariCP and PostgreSQL driver (available)
- Python packages from `requirements.txt`: `kafka-python`, `pika`, `redis`

Infrastructure/runtime services:
- Kafka
- RabbitMQ
- Redis
- Spring Boot app
- Flink job
- Optional: Postgres, LocalStack

## 8. Notes and implementation caveats

- The active monitoring dashboard depends on consumer-side Redis updates (`--redis-enabled`) to reflect latest alert states.
- Postgres, DynamoDB, and S3 are configured for extensibility but are not part of the default active telemetry path.
- The quick-start docs mention `scripts/requirements.txt`, while dependency file present in repository root is `requirements.txt`.
