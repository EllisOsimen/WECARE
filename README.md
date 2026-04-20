# Getting Started

## Eldercare Telemetry PoC Quick Start

### 1) Start infrastructure

```powershell
docker compose up -d kafka rabbitmq redis localstack
```

### 2) Start the Spring Boot app (pre-creates topic and queues)

On startup, this app now declares:

- Kafka topic: `elder-vitals-inbound`
- RabbitMQ queues: `nurse-urgent-alerts`, `nurse-warnings`, `alert-archive`

And initializes DynamoDB alert history persistence (LocalStack-backed):

- Table: `wecare-alert-history`
- Consumer queue: `alert-archive`

Run:

```powershell
./mvnw spring-boot:run
```

### 3) Run the telemetry simulator

In a second terminal:

```powershell
pip install -r requirements.txt
python scripts/patient_telemetry_simulator.py --topic elder-vitals-inbound --interval-seconds 1
```

### 4) Inject anomalies while simulator is running

Type into the simulator terminal:

- `crash 3` (forces low oxygen around 85 for patient 3)
- `wander 3` (forces patient 3 to `Exit Gate`)
- `fall 3` (forces `Fall Detected`)
- `recover 3` (returns patient 3 to normal behavior and clears active alert status from dashboard)

Type `exit` to stop the simulator.

### 5) Run the Flink brains pipeline

Run the Flink job main class from Maven:

```powershell
./mvnw -DskipTests spring-boot:run "-Dspring-boot.run.main-class=uk.ac.ed.inf.wecare.flink.EldercareAlertPipeline" "-Dspring-boot.run.arguments=--kafka-bootstrap localhost:9092 --kafka-topic elder-vitals-inbound --rabbit-host localhost --rabbit-port 5672 --urgent-queue nurse-urgent-alerts --warning-queue nurse-warnings --archive-queue alert-archive"
```

Implemented rules:

- Rule 1 (stateless): `oxygen_level < 90` => URGENT alert
- Rule 2 (stateful): 10-second tumbling window average heart rate `< 50` or `> 120` => trend alert
- Rule 3 (geofence): `location_zone == Exit Gate` => URGENT alert
- Rule 4 (fall): `movement_status == Fall Detected` => URGENT alert

Routing:

- URGENT alerts -> `nurse-urgent-alerts`
- WARNING alerts -> `nurse-warnings`
- ALL alerts (append-only archive stream) -> `alert-archive`

### 6) Run the nurse station consumers

In separate terminals (side-by-side demo):

```powershell
python scripts/nurse_station_consumer.py --queue nurse-urgent-alerts
python scripts/nurse_warning_consumer.py
```

Optional Redis status updates (urgent and warning):

```powershell
python scripts/nurse_station_consumer.py --queue nurse-urgent-alerts --redis-enabled --redis-host localhost --redis-port 6379
python scripts/nurse_station_consumer.py --queue nurse-warnings --station-mode warning --redis-enabled --redis-host localhost --redis-port 6379
```

### 7) Verify DynamoDB historical alert log

All alerts are archived by the Spring Boot listener into DynamoDB table `wecare-alert-history`.

If AWS CLI is installed:

```powershell
aws dynamodb scan --table-name wecare-alert-history --endpoint-url http://localhost:4566 --region us-east-1
```

You should see append-only items including fields such as `alert_id`, `rule`, `severity`, `patient_id`, `source_queue`, `detected_at`, and `raw_payload`.

### 8) Open the browser dashboard

With Spring Boot running, open:

- `http://localhost:8080/` (redirects to dashboard)
- or `http://localhost:8080/dashboard.html`

Dashboard features:

- Live patient status cards from Redis hashes (`patient_<id>_status`)
- Pin/Unpin buttons stored in Redis set (`pinned_patients`)
- Auto-refresh every 3 seconds

REST endpoints used by dashboard:

- `GET /api/patients/status`
- `POST /api/patients/{patientId}/pin`
- `DELETE /api/patients/{patientId}/pin`

### Reference Documentation

For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/4.0.5/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.0.5/maven-plugin/build-image.html)
* [Spring Boot DevTools](https://docs.spring.io/spring-boot/4.0.5/reference/using/devtools.html)

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the
parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.

