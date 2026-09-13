package com.pollution.common.config;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

public final class Config {

    public static final String POLLUTION_DATA_TOPIC = "pollution-data";
    public static final String POLLUTION_AVERAGE_TOPIC = "pollution-average";

    private static final String DEFAULT_KAFKA_HOST = "localhost";
    private static final int DEFAULT_KAFKA_PORT = 9092;
    /** The {@code HEALTH_PORT} value meaning no health server, which is also what unset means. */
    private static final int NO_HEALTH_PORT = 0;

    private Config() {
    }

    /**
     * The port the health server answers a container platform's probes on
     * ({@code /healthz} for liveness, {@code /readyz} for readiness — see
     * {@link com.pollution.common.health.IHealthServer}), when
     * {@code HEALTH_PORT} names one. Empty when it is unset or 0, the
     * default, so every service can run on one machine without choosing
     * ports; a pod's manifest sets it.
     */
    public static OptionalInt getHealthPort() {
        int port = Env.getInt("HEALTH_PORT", NO_HEALTH_PORT);
        return port == NO_HEALTH_PORT ? OptionalInt.empty() : OptionalInt.of(port);
    }

    public static String getKafkaHost() {
        return Env.getString("KAFKA_HOST", DEFAULT_KAFKA_HOST);
    }

    public static int getKafkaPort() {
        return Env.getInt("KAFKA_PORT", DEFAULT_KAFKA_PORT);
    }

    public static String getKafkaBootstrapServers() {
        return getKafkaHost() + ":" + getKafkaPort();
    }

    /**
     * The thresholds file to read instead of the {@code thresholds.json}
     * packaged in this module, when {@code THRESHOLDS_FILE} names one; see
     * {@link com.pollution.common.thresholds.ThresholdsLoader}.
     */
    public static Optional<Path> getThresholdsFile() {
        String file = Env.getString("THRESHOLDS_FILE", "");
        return file.isBlank() ? Optional.empty() : Optional.of(Path.of(file));
    }
}
