package com.pollution.alertservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pollution.alertservice.entities.AlertSeries;
import com.pollution.alertservice.persistence.AlertCooldowns;
import com.pollution.alertservice.persistence.IAlertCooldownStore;
import com.pollution.alertservice.persistence.InMemoryAlertCooldownStore;
import com.pollution.alertservice.senders.AlertSendException;
import com.pollution.alertservice.senders.RecordingAlertSender;
import com.pollution.common.entities.Pollutant;
import com.pollution.common.entities.PollutionAlert;
import com.pollution.common.entities.PollutionAverage;
import com.pollution.common.entities.PollutionData;
import com.pollution.common.entities.WindowAverage;
import com.pollution.common.testing.ManualSubscriber;
import com.pollution.common.testing.MutableClock;
import com.pollution.persistence.PollutionCacheException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The alert service alone: readings and averages are delivered into it by
 * hand, and what it sends and suppresses is asserted. Thresholds
 * are {@link TestThresholds}; every window's cooldown is its own length and
 * a single reading's is ten minutes.
 */
class PollutionAlertServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-05T10:00:00Z");
    private static final String CITY = "Tel Aviv";
    private static final String SOURCE = "purpleair:Ganei-Ayalon";
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofHours(24);
    private static final Duration READING_COOLDOWN = Duration.ofMinutes(10);

    private final ManualSubscriber<PollutionData> readings = new ManualSubscriber<>();
    private final ManualSubscriber<PollutionAverage> averages = new ManualSubscriber<>();
    private final RecordingAlertSender sender = new RecordingAlertSender();
    private final MutableClock clock = MutableClock.at(T0);
    private final IAlertCooldownStore cooldowns =
            new InMemoryAlertCooldownStore(new AlertCooldowns(Map.of(), READING_COOLDOWN), clock);
    private final PollutionAlertService service =
            new PollutionAlertService(readings, averages, TestThresholds.detector(), cooldowns, sender);

    @BeforeEach
    void start() {
        service.start();
    }

    private static PollutionData reading(String source, double value) {
        return new PollutionData(CITY, source, Pollutant.PM2_5, value, T0);
    }

    private static PollutionData reading(double value) {
        return reading(SOURCE, value);
    }

    private static PollutionAverage average(WindowAverage... windows) {
        return new PollutionAverage(CITY, SOURCE, Pollutant.PM2_5, List.of(windows), T0);
    }

    private static WindowAverage over(Duration window, double mean) {
        return new WindowAverage(window, mean, 6);
    }

    private static List<Duration> windowsOf(List<PollutionAlert> alerts) {
        return alerts.stream().map(PollutionAlert::window).toList();
    }

    @Test
    void aReadingAboveItsThresholdIsSent() {
        readings.deliver(reading(60));

        assertEquals(List.of(new PollutionAlert(CITY, SOURCE, Pollutant.PM2_5, null, 60, 50, T0)), sender.sent());
    }

    @Test
    void aReadingBelowItsThresholdRaisesNothing() {
        readings.deliver(reading(50));

        assertEquals(List.of(), sender.attempted());
    }

    @Test
    void anAverageAboveItsThresholdIsSentWithItsWindow() {
        averages.deliver(average(over(TEN_MINUTES, 30), over(HOUR, 30), over(DAY, 26)));

        assertEquals(List.of(new PollutionAlert(CITY, SOURCE, Pollutant.PM2_5, DAY, 26, 25, T0)), sender.sent());
    }

    @Test
    void aRepeatWithinTheCooldownIsDroppedAndRaisedAgainAfterIt() {
        readings.deliver(reading(60));
        readings.deliver(reading(61));
        clock.advance(READING_COOLDOWN.minusSeconds(1));
        readings.deliver(reading(62));

        assertEquals(1, sender.sent().size());

        clock.advance(Duration.ofSeconds(1));
        readings.deliver(reading(63));

        assertEquals(2, sender.sent().size());
        assertEquals(63, sender.sent().get(1).measuredValue());
    }

    @Test
    void whenSeveralWindowsOfOneMessageExceedOnlyTheLongestIsRaised() {
        averages.deliver(average(over(TEN_MINUTES, 40), over(HOUR, 40), over(DAY, 20)));

        assertEquals(List.of(HOUR), windowsOf(sender.sent()));
    }

    @Test
    void theLongestWindowIsRaisedAgainEachTimeItsCooldownLapsesNeverTheShorter() {
        averages.deliver(average(over(TEN_MINUTES, 40), over(HOUR, 40)));
        clock.advance(HOUR.minusSeconds(1));
        averages.deliver(average(over(TEN_MINUTES, 40), over(HOUR, 40)));

        assertEquals(List.of(HOUR), windowsOf(sender.sent()));

        clock.advance(Duration.ofSeconds(1));
        averages.deliver(average(over(TEN_MINUTES, 40), over(HOUR, 40)));

        assertEquals(List.of(HOUR, HOUR), windowsOf(sender.sent()));
    }

    @Test
    void aShorterMeasurementIsOldNewsWhileALongerOneIsAlerting() {
        averages.deliver(average(over(TEN_MINUTES, 20), over(HOUR, 40)));
        readings.deliver(reading(60));
        averages.deliver(average(over(TEN_MINUTES, 40), over(HOUR, 20)));

        assertEquals(List.of(HOUR), windowsOf(sender.sent()));
    }

    @Test
    void aShorterMeasurementAlertsOnceTheLongerOnesCooldownHasLapsed() {
        averages.deliver(average(over(TEN_MINUTES, 20), over(HOUR, 40)));
        clock.advance(HOUR);
        averages.deliver(average(over(TEN_MINUTES, 40), over(HOUR, 20)));

        assertEquals(List.of(HOUR, TEN_MINUTES), windowsOf(sender.sent()));
    }

    @Test
    void aLongerMeasurementIsNotSilencedByAShorterOne() {
        readings.deliver(reading(60));
        averages.deliver(average(over(TEN_MINUTES, 40), over(HOUR, 20)));
        averages.deliver(average(over(TEN_MINUTES, 40), over(HOUR, 40)));

        assertEquals(Arrays.asList(null, TEN_MINUTES, HOUR), windowsOf(sender.sent()));
    }

    @Test
    void differentSourcesAndPollutantsHaveIndependentCooldowns() {
        readings.deliver(reading(SOURCE, 60));
        readings.deliver(reading("purpleair:Shoham", 60));
        readings.deliver(new PollutionData(CITY, SOURCE, Pollutant.PM10, 250, T0));
        readings.deliver(reading(SOURCE, 60));

        assertEquals(3, sender.sent().size());
    }

    @Test
    void aFailingChannelStillStartsTheCooldown() {
        sender.failWith(new AlertSendException("telegram is down"));

        readings.deliver(reading(60));
        readings.deliver(reading(61));

        assertEquals(1, sender.attempted().size(), "not retried at the rate readings arrive");
        assertEquals(List.of(), sender.sent());
        assertEquals(Set.of(new AlertSeries(SOURCE, Pollutant.PM2_5, null)), cooldowns.coolingDownSeries(SOURCE, Pollutant.PM2_5));
    }

    @Test
    void anUnreadableCooldownStoreAlertsAnywayRatherThanMissOne() {
        ManualSubscriber<PollutionData> readings = new ManualSubscriber<>();
        PollutionAlertService service = new PollutionAlertService(readings, new ManualSubscriber<>(),
                TestThresholds.detector(), new UnreadableCooldownStore(), sender);
        service.start();

        readings.deliver(reading(60));
        readings.deliver(reading(61));

        assertEquals(2, sender.sent().size());
    }

    @Test
    void theAlertCarriesTheMeasurementItsThresholdAndTheMessagesTime() {
        Instant later = T0.plus(Duration.ofMinutes(3));
        averages.deliver(new PollutionAverage(CITY, SOURCE, Pollutant.PM2_5, List.of(over(TEN_MINUTES, 35)), later));

        PollutionAlert alert = sender.sent().get(0);
        assertEquals(35, alert.measuredValue());
        assertEquals(33.75, alert.threshold());
        assertEquals(later, alert.timestamp());
        assertEquals(TEN_MINUTES, alert.window());
        assertEquals(CITY, alert.city());
    }

    @Test
    void aReadingAlertHasNoWindow() {
        readings.deliver(reading(60));

        assertNull(sender.sent().get(0).window());
    }

    @Test
    void closingClosesEverythingItOwns() {
        service.close();

        assertTrue(readings.isClosed());
        assertTrue(averages.isClosed());
        assertTrue(sender.isClosed());
    }

    /** A cooldown store whose cache is down: nothing can be read, marking is accepted. */
    private static final class UnreadableCooldownStore implements IAlertCooldownStore {

        @Override
        public Set<AlertSeries> coolingDownSeries(String source, Pollutant pollutant) {
            throw new PollutionCacheException("redis is down", null);
        }

        @Override
        public void markSent(PollutionAlert alert) {
            throw new PollutionCacheException("redis is down", null);
        }

        @Override
        public void close() {
        }
    }
}
