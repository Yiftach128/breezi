package com.pollution.alertservice;

import com.pollution.alertservice.detection.ThresholdDetector;
import com.pollution.alertservice.entities.AlertSeries;
import com.pollution.alertservice.persistence.IAlertCooldownStore;
import com.pollution.alertservice.senders.IAlertSender;
import com.pollution.common.PollutionLogger;
import com.pollution.common.entities.PollutionAlert;
import com.pollution.common.entities.PollutionAverage;
import com.pollution.common.entities.PollutionData;
import com.pollution.common.pubsub.ISubscriber;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Consumes the streams of {@link PollutionData} readings and
 * {@link PollutionAverage}s, runs each through a {@link ThresholdDetector},
 * and raises every alert it finds by delivering it as a {@link PollutionAlert}
 * through the {@link IAlertSender}; a failed delivery is logged, and the
 * service's own log of every raised alert is the record whatever the
 * channel.
 * <p>
 * A series (source, pollutant, window) alerts at most once per its cooldown,
 * tracked in the {@link IAlertCooldownStore}; and while a longer measurement
 * of the same source and pollutant is cooling down, shorter ones are old
 * news and stay quiet (a spike is not worth a post when the hour is already
 * alerting). For that rule to apply within one message too, the alerts of
 * one {@link PollutionAverage} are raised longest window first: when the
 * hour and the ten minutes exceed at once, the hour's alert goes out and the
 * ten minutes' is dropped as superseded — one post, not two, and the same
 * again each time the hour's cooldown lapses. The cooldown starts after the
 * delivery attempt, whether or not it succeeded — the inputs repeat every
 * few seconds, so retrying a broken channel would only flood it. If the store cannot be read the alert is
 * raised anyway: an outage may cause duplicate alerts, never missed ones.
 * <p>
 * Messages arrive on the two subscribers' threads; {@link #lock} serializes
 * raising, so the sender is never entered concurrently and the cooldown
 * check-then-mark is atomic within this instance.
 */
public class PollutionAlertService implements AutoCloseable {

    private static final Logger logger = PollutionLogger.getLogger(PollutionAlertService.class);

    /** The order a message's average alerts are raised in; every average alert has a window. */
    private static final Comparator<PollutionAlert> LONGEST_WINDOW_FIRST =
            Comparator.comparing(PollutionAlert::window, Comparator.reverseOrder());

    private final ISubscriber<PollutionData> pollutionSubscriber;
    private final ISubscriber<PollutionAverage> averageSubscriber;
    private final ThresholdDetector detector;
    private final IAlertCooldownStore cooldownStore;
    private final IAlertSender alertSender;

    private final Object lock = new Object();

    public PollutionAlertService(ISubscriber<PollutionData> pollutionSubscriber,
                                 ISubscriber<PollutionAverage> averageSubscriber,
                                 ThresholdDetector detector,
                                 IAlertCooldownStore cooldownStore,
                                 IAlertSender alertSender) {
        this.pollutionSubscriber = Objects.requireNonNull(pollutionSubscriber, "pollutionSubscriber");
        this.averageSubscriber = Objects.requireNonNull(averageSubscriber, "averageSubscriber");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.cooldownStore = Objects.requireNonNull(cooldownStore, "cooldownStore");
        this.alertSender = Objects.requireNonNull(alertSender, "alertSender");
    }

    public void start() {
        pollutionSubscriber.subscribe(this::handleReading);
        averageSubscriber.subscribe(this::handleAverage);
        logger.info("subscribed to pollution readings and averages");
    }

    private void handleReading(PollutionData reading) {
        logger.debug("received {}", reading);
        detector.detect(reading).forEach(this::raise);
    }

    private void handleAverage(PollutionAverage average) {
        logger.debug("received {}", average);
        // longest window first: once the hour's alert is out, the 10-minute one of the same message is old news
        detector.detect(average).stream().sorted(LONGEST_WINDOW_FIRST).forEach(this::raise);
    }

    private void raise(PollutionAlert alert) {
        AlertSeries series = AlertSeries.of(alert);
        synchronized (lock) {
            Set<AlertSeries> coolingDown = coolingDownSeries(series);
            if (coolingDown.contains(series)) {
                logger.debug("{}: still cooling down; suppressed {}", series, alert);
                return;
            }
            Optional<AlertSeries> longer = coolingDown.stream().filter(other -> other.supersedes(series)).findFirst();
            if (longer.isPresent()) {
                logger.debug("{}: old news while {} is alerting; suppressed {}", series, longer.get(), alert);
                return;
            }
            send(alert);
            markSent(alert);
        }
        logger.info("raised {}", alert);
    }

    /** A store that cannot be read counts as nothing cooling down: better a duplicate alert than a missed one. */
    private Set<AlertSeries> coolingDownSeries(AlertSeries series) {
        try {
            return cooldownStore.coolingDownSeries(series.source(), series.pollutant());
        } catch (RuntimeException e) {
            logger.error("{}: failed to read cooldown state; alerting anyway", series, e);
            return Set.of();
        }
    }

    private void send(PollutionAlert alert) {
        try {
            alertSender.send(alert);
        } catch (RuntimeException e) {
            logger.error("failed to send {}", alert, e);
        }
    }

    private void markSent(PollutionAlert alert) {
        try {
            cooldownStore.markSent(alert);
        } catch (RuntimeException e) {
            logger.error("failed to record cooldown for {}", alert, e);
        }
    }

    @Override
    public void close() {
        pollutionSubscriber.close();
        averageSubscriber.close();
        cooldownStore.close();
        alertSender.close();
    }
}
