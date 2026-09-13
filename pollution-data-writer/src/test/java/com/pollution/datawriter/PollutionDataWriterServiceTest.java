package com.pollution.datawriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pollution.common.entities.Pollutant;
import com.pollution.common.entities.PollutionAverage;
import com.pollution.common.entities.PollutionData;
import com.pollution.common.entities.WindowAverage;
import com.pollution.common.testing.ManualSubscriber;
import com.pollution.persistence.PollutionCacheException;
import com.pollution.persistence.PollutionRepositoryException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The writer alone: messages are delivered into it by hand, and what reaches each store is asserted. */
class PollutionDataWriterServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-05T10:00:00Z");
    private static final String CITY = "Tel Aviv";
    private static final String SOURCE = "purpleair:Ganei-Ayalon";

    private final ManualSubscriber<PollutionData> readings = new ManualSubscriber<>();
    private final ManualSubscriber<PollutionAverage> averages = new ManualSubscriber<>();
    private final RecordingRepository repository = new RecordingRepository();
    private final RecordingLatestReadingStore latest = new RecordingLatestReadingStore();
    private final PollutionDataWriterService service =
            new PollutionDataWriterService(readings, averages, repository, latest);

    @BeforeEach
    void start() {
        service.start();
    }

    private static PollutionData reading(double value, Instant at) {
        return new PollutionData(CITY, SOURCE, Pollutant.PM2_5, value, at);
    }

    @Test
    void aReadingIsStoredForKeepsAndMadeTheSeriesCurrentOne() {
        PollutionData reading = reading(12.5, T0);

        readings.deliver(reading);

        assertEquals(List.of(reading), repository.saved());
        assertEquals(Optional.of(reading), latest.find(SOURCE, Pollutant.PM2_5));
    }

    @Test
    void aRedeliveredReadingIsOfferedToBothStoresAgain() {
        PollutionData reading = reading(12.5, T0);

        readings.deliver(reading);
        readings.deliver(reading);

        assertEquals(List.of(reading, reading), repository.saved(), "the repository is idempotent, so it is asked");
        assertEquals(List.of(reading, reading), latest.saved());
    }

    @Test
    void anOlderReadingIsStoredForKeepsButDoesNotMoveTheSeriesBackwards() {
        PollutionData newer = reading(14, T0.plusSeconds(10));
        PollutionData older = reading(12.5, T0);

        readings.deliver(newer);
        readings.deliver(older);

        assertEquals(List.of(newer, older), repository.saved());
        assertEquals(Optional.of(newer), latest.find(SOURCE, Pollutant.PM2_5));
    }

    @Test
    void aFailingRepositoryDoesNotStopTheCurrentReading() {
        repository.failWith(new PollutionRepositoryException("postgres is down", null));
        PollutionData reading = reading(12.5, T0);

        readings.deliver(reading);

        assertEquals(Optional.of(reading), latest.find(SOURCE, Pollutant.PM2_5));
    }

    @Test
    void aFailingCurrentReadingStoreDoesNotStopTheRepository() {
        latest.failWith(new PollutionCacheException("redis is down", null));
        PollutionData reading = reading(12.5, T0);

        readings.deliver(reading);

        assertEquals(List.of(reading), repository.saved());
    }

    @Test
    void aFailureOnOneReadingDoesNotAffectTheNext() {
        repository.failWith(new PollutionRepositoryException("postgres is down", null));
        readings.deliver(reading(12.5, T0));
        repository.failWith(null);

        readings.deliver(reading(13, T0.plusSeconds(10)));

        assertEquals(List.of(reading(13, T0.plusSeconds(10))), repository.saved());
    }

    @Test
    void averagesAreOnlyLoggedForNow() {
        averages.deliver(new PollutionAverage(CITY, SOURCE, Pollutant.PM2_5,
                List.of(new WindowAverage(Duration.ofMinutes(10), 12.5, 3)), T0));

        assertEquals(List.of(), repository.saved());
        assertEquals(List.of(), latest.saved());
    }

    @Test
    void closingClosesEverythingItOwns() {
        service.close();

        assertTrue(readings.isClosed());
        assertTrue(averages.isClosed());
        assertTrue(repository.isClosed());
        assertTrue(latest.isClosed());
    }
}
