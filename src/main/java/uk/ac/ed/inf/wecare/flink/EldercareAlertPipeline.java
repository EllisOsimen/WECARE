package uk.ac.ed.inf.wecare.flink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Flink job for eldercare anomaly detection and alert routing.
 *
 * Rule 1 (stateless): oxygen_level < 90 => URGENT
 * Rule 2 (stateful): 10-second average heart rate < 50 or > 120 => WARNING/URGENT
 * Rule 3 (stateless): location_zone == Exit Gate => URGENT
 */
public class EldercareAlertPipeline {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        PipelineConfig config = PipelineConfig.fromArgs(args);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(config.parallelism());

        KafkaSource<String> kafkaSource = KafkaSource.<String>builder()
                .setBootstrapServers(config.kafkaBootstrapServers())
                .setTopics(config.kafkaTopic())
                .setGroupId(config.kafkaGroupId())
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<PatientTelemetry> telemetry = env
                .fromSource(kafkaSource, org.apache.flink.api.common.eventtime.WatermarkStrategy.noWatermarks(), "elder-vitals-source")
                .flatMap(new TelemetryParser())
                .returns(TypeInformation.of(PatientTelemetry.class));

        DataStream<AlertEnvelope> vitalsAlerts = telemetry
                .filter(t -> t.oxygenLevel() < 90)
                .map(EldercareAlertPipeline::buildVitalsAlert)
                .returns(TypeInformation.of(AlertEnvelope.class));

        DataStream<AlertEnvelope> geofenceAlerts = telemetry
                .filter(t -> "Exit Gate".equalsIgnoreCase(t.locationZone()))
                .map(EldercareAlertPipeline::buildGeofenceAlert)
                .returns(TypeInformation.of(AlertEnvelope.class));

        DataStream<AlertEnvelope> fallAlerts = telemetry
            .filter(t -> "Fall Detected".equalsIgnoreCase(t.movementStatus()))
            .map(EldercareAlertPipeline::buildFallAlert)
            .returns(TypeInformation.of(AlertEnvelope.class));

        DataStream<AlertEnvelope> recoveryAlerts = telemetry
            .filter(t -> "Recovered".equalsIgnoreCase(t.movementStatus()))
            .map(EldercareAlertPipeline::buildRecoveryAlert)
            .returns(TypeInformation.of(AlertEnvelope.class));

        DataStream<AlertEnvelope> heartTrendAlerts = telemetry
                .keyBy(PatientTelemetry::patientId)
                .window(TumblingProcessingTimeWindows.of(Time.seconds(10)))
                .process(new HeartRateTrendRule())
                .returns(TypeInformation.of(AlertEnvelope.class));

        DataStream<AlertEnvelope> allAlerts = vitalsAlerts
                .union(geofenceAlerts)
            .union(fallAlerts)
            .union(recoveryAlerts)
            .union(heartTrendAlerts);

        allAlerts
                .filter(AlertEnvelope::isUrgent)
                .addSink(new RabbitMqAlertSink(
                        config.rabbitHost(),
                        config.rabbitPort(),
                        config.rabbitUsername(),
                        config.rabbitPassword(),
                config.urgentQueue()));

        allAlerts
                .filter(alert -> !alert.isUrgent())
                .addSink(new RabbitMqAlertSink(
                        config.rabbitHost(),
                        config.rabbitPort(),
                        config.rabbitUsername(),
                        config.rabbitPassword(),
                config.warningQueue()));

        allAlerts
            .addSink(new RabbitMqAlertSink(
                config.rabbitHost(),
                config.rabbitPort(),
                config.rabbitUsername(),
                config.rabbitPassword(),
                config.archiveQueue()));

        env.execute("WeCare Eldercare Alert Pipeline");
    }

    private static AlertEnvelope buildVitalsAlert(PatientTelemetry telemetry) {
        String message = String.format(
                "Critical oxygen event for patient %d in %s: SpO2=%d%%",
                telemetry.patientId(),
                telemetry.locationZone(),
                telemetry.oxygenLevel()
        );

        return new AlertEnvelope(
                "URGENT",
                buildAlertJson(
                        telemetry,
                        "VITALS_OXYGEN_LOW",
                        "CRITICAL",
                        message,
                        null,
                        null,
                        null
                )
        );
    }

    private static AlertEnvelope buildGeofenceAlert(PatientTelemetry telemetry) {
        String riskTag = telemetry.dementiaFlag() ? "HIGH_RISK" : "ELEVATED_RISK";
        String message = String.format(
                "Geofence breach: patient %d entered Exit Gate (dementia=%s)",
                telemetry.patientId(),
                telemetry.dementiaFlag()
        );

        return new AlertEnvelope(
                "URGENT",
                buildAlertJson(
                        telemetry,
                        "GEOFENCE_EXIT_GATE",
                        riskTag,
                        message,
                        null,
                        null,
                        null
                )
        );
    }

            private static AlertEnvelope buildFallAlert(PatientTelemetry telemetry) {
            String message = String.format(
                "Fall detected for patient %d in %s",
                telemetry.patientId(),
                telemetry.locationZone()
            );

            return new AlertEnvelope(
                "URGENT",
                buildAlertJson(
                    telemetry,
                    "FALL_DETECTED",
                    "CRITICAL",
                    message,
                    null,
                    null,
                    null
                )
            );
            }

            private static AlertEnvelope buildRecoveryAlert(PatientTelemetry telemetry) {
            String message = String.format(
                "Patient %d recovered and returned to baseline telemetry",
                telemetry.patientId()
            );

            return new AlertEnvelope(
                "URGENT",
                buildAlertJson(
                    telemetry,
                    "PATIENT_RECOVERED",
                    "NORMAL",
                    message,
                    null,
                    null,
                    null
                )
            );
            }

    private static String buildAlertJson(
            PatientTelemetry telemetry,
            String rule,
            String severity,
            String message,
            Double avgHeartRate,
            String windowStart,
            String windowEnd
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("alert_id", UUID.randomUUID().toString());
        payload.put("rule", rule);
        payload.put("severity", severity);
        payload.put("patient_id", telemetry.patientId());
        payload.put("message", message);
        payload.put("heart_rate", telemetry.heartRate());
        payload.put("oxygen_level", telemetry.oxygenLevel());
        payload.put("location_zone", telemetry.locationZone());
        payload.put("movement_status", telemetry.movementStatus());
        payload.put("dementia_flag", telemetry.dementiaFlag());
        payload.put("telemetry_timestamp", telemetry.timestamp());
        payload.put("detected_at", Instant.now().toString());

        if (avgHeartRate != null) {
            payload.put("avg_heart_rate", avgHeartRate);
        }
        if (windowStart != null) {
            payload.put("window_start", windowStart);
        }
        if (windowEnd != null) {
            payload.put("window_end", windowEnd);
        }

        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception error) {
            return "{\"message\":\"alert serialization failed\",\"patient_id\":" + telemetry.patientId() + "}";
        }
    }

    public static class AlertEnvelope implements Serializable {
        public String priority;
        public String payload;

        public AlertEnvelope() {
        }

        AlertEnvelope(String priority, String payload) {
            this.priority = priority;
            this.payload = payload;
        }

        boolean isUrgent() {
            return "URGENT".equalsIgnoreCase(priority);
        }

        String payload() {
            return payload;
        }
    }

    public static class PatientTelemetry implements Serializable {
        public int patientId;
        public int heartRate;
        public int oxygenLevel;
        public String locationZone;
        public String movementStatus;
        public boolean dementiaFlag;
        public String timestamp;

        public PatientTelemetry() {
        }

        PatientTelemetry(
                int patientId,
                int heartRate,
                int oxygenLevel,
                String locationZone,
                String movementStatus,
                boolean dementiaFlag,
                String timestamp
        ) {
            this.patientId = patientId;
            this.heartRate = heartRate;
            this.oxygenLevel = oxygenLevel;
            this.locationZone = locationZone;
            this.movementStatus = movementStatus;
            this.dementiaFlag = dementiaFlag;
            this.timestamp = timestamp;
        }

        int patientId() {
            return patientId;
        }

        int heartRate() {
            return heartRate;
        }

        int oxygenLevel() {
            return oxygenLevel;
        }

        String locationZone() {
            return locationZone;
        }

        String movementStatus() {
            return movementStatus;
        }

        boolean dementiaFlag() {
            return dementiaFlag;
        }

        String timestamp() {
            return timestamp;
        }
    }

    private static class TelemetryParser implements FlatMapFunction<String, PatientTelemetry> {
        private final ObjectMapper parser = new ObjectMapper();

        @Override
        public void flatMap(String rawJson, Collector<PatientTelemetry> out) {
            try {
                JsonNode node = parser.readTree(rawJson);
                JsonNode patientIdNode = node.get("patient_id");
                JsonNode heartRateNode = node.get("heart_rate");
                JsonNode oxygenNode = node.get("oxygen_level");

                if (patientIdNode == null || heartRateNode == null || oxygenNode == null) {
                    return;
                }

                int patientId = patientIdNode.asInt();
                int heartRate = heartRateNode.asInt();
                int oxygenLevel = oxygenNode.asInt();
                String locationZone = node.path("location_zone").asText("Unknown");
                String movementStatus = node.path("movement_status").asText("Unknown");
                boolean dementiaFlag = node.path("dementia_flag").asBoolean(false);
                String timestamp = node.path("timestamp").asText(Instant.now().toString());

                out.collect(new PatientTelemetry(
                        patientId,
                        heartRate,
                        oxygenLevel,
                        locationZone,
                        movementStatus,
                        dementiaFlag,
                        timestamp
                ));
            } catch (JsonProcessingException parseError) {
                System.err.println("Skipping malformed telemetry payload: " + rawJson);
            } catch (Exception processingError) {
                System.err.println("Failed to process telemetry payload: " + rawJson);
                processingError.printStackTrace(System.err);
            }
        }
    }

    private static class HeartRateTrendRule extends ProcessWindowFunction<PatientTelemetry, AlertEnvelope, Integer, TimeWindow> {
        @Override
        public void process(
                Integer patientId,
                ProcessWindowFunction<PatientTelemetry, AlertEnvelope, Integer, TimeWindow>.Context context,
                Iterable<PatientTelemetry> elements,
                Collector<AlertEnvelope> out
        ) {
            double total = 0;
            int count = 0;
            PatientTelemetry latestTelemetry = null;

            for (PatientTelemetry telemetry : elements) {
                latestTelemetry = telemetry;
                total += telemetry.heartRate();
                count++;
            }

            if (latestTelemetry == null || count == 0) {
                return;
            }

            double averageHeartRate = total / count;
            if (averageHeartRate < 50 || averageHeartRate > 120) {
                String priority = (averageHeartRate < 45 || averageHeartRate > 130) ? "URGENT" : "WARNING";
                String windowStart = Instant.ofEpochMilli(context.window().getStart()).toString();
                String windowEnd = Instant.ofEpochMilli(context.window().getEnd()).toString();

                String message = String.format(
                        "Heart-rate trend alert for patient %d: avg bpm %.1f over 10s window",
                        patientId,
                        averageHeartRate
                );

                String payload = buildAlertJson(
                        latestTelemetry,
                        "HEART_RATE_TREND_10S",
                        "TREND_ALERT",
                        message,
                        averageHeartRate,
                        windowStart,
                        windowEnd
                );

                out.collect(new AlertEnvelope(priority, payload));
            }
        }
    }

    private static class RabbitMqAlertSink extends RichSinkFunction<AlertEnvelope> {
        private final String host;
        private final int port;
        private final String username;
        private final String password;
        private final String queueName;

        private transient Connection connection;
        private transient Channel channel;

        RabbitMqAlertSink(String host, int port, String username, String password, String queueName) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
            this.queueName = queueName;
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(host);
            factory.setPort(port);
            factory.setUsername(username);
            factory.setPassword(password);

            connection = factory.newConnection("wecare-flink-rabbit-sink");
            channel = connection.createChannel();
            channel.queueDeclare(queueName, true, false, false, null);
        }

        @Override
        public void invoke(AlertEnvelope alert, Context context) throws Exception {
            byte[] body = alert.payload().getBytes(StandardCharsets.UTF_8);
            channel.basicPublish("", queueName, MessageProperties.PERSISTENT_TEXT_PLAIN, body);
        }

        @Override
        public void close() throws Exception {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
            super.close();
        }
    }

    private record PipelineConfig(
            String kafkaBootstrapServers,
            String kafkaTopic,
            String kafkaGroupId,
            String rabbitHost,
            int rabbitPort,
            String rabbitUsername,
            String rabbitPassword,
            String urgentQueue,
            String warningQueue,
            String archiveQueue,
            int parallelism
    ) {
        static PipelineConfig fromArgs(String[] args) {
            Map<String, String> values = parseArgs(args);

            return new PipelineConfig(
                    values.getOrDefault("kafka-bootstrap", "localhost:9092"),
                    values.getOrDefault("kafka-topic", "elder-vitals-inbound"),
                    values.getOrDefault("kafka-group", "wecare-flink-brains"),
                    values.getOrDefault("rabbit-host", "localhost"),
                    Integer.parseInt(values.getOrDefault("rabbit-port", "5672")),
                    values.getOrDefault("rabbit-username", "guest"),
                    values.getOrDefault("rabbit-password", "guest"),
                    values.getOrDefault("urgent-queue", "nurse-urgent-alerts"),
                    values.getOrDefault("warning-queue", "nurse-warnings"),
                    values.getOrDefault("archive-queue", "alert-archive"),
                    Integer.parseInt(values.getOrDefault("parallelism", "1"))
            );
        }

        private static Map<String, String> parseArgs(String[] args) {
            Map<String, String> values = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    continue;
                }

                String key = arg.substring(2);
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    values.put(key, "true");
                    continue;
                }

                values.put(key, args[++i]);
            }
            return values;
        }
    }
}
