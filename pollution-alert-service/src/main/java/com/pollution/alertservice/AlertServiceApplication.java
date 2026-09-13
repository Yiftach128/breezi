package com.pollution.alertservice;

import com.pollution.alertservice.config.Config;
import com.pollution.alertservice.config.Wiring;
import com.pollution.alertservice.persistence.IAlertCooldownStore;
import com.pollution.alertservice.senders.IAlertSender;
import com.pollution.common.PollutionLogger;
import com.pollution.common.entities.PollutionAverage;
import com.pollution.common.entities.PollutionData;
import com.pollution.common.health.IHealthServer;
import com.pollution.common.pubsub.ISubscriber;
import com.pollution.common.thresholds.Thresholds;
import org.slf4j.Logger;

public class AlertServiceApplication {

    /* Runs before the logger field below triggers logback's configuration:
       static initializers execute in textual order (JLS 12.4.2), and
       Config.SERVICE_NAME is a compile-time constant, so reading it here
       does not initialize Config or anything Config touches. */
    static {
        PollutionLogger.initService(Config.SERVICE_NAME);
    }

    private static final Logger logger = PollutionLogger.getLogger(AlertServiceApplication.class);

    public static void main(String[] args) {
        logger.info("{} starting", Config.SERVICE_NAME);
        IHealthServer health = Wiring.createHealthServer();
        health.start();
        try {
            ISubscriber<PollutionData> pollutionSubscriber = Wiring.createPollutionSubscriber();
            ISubscriber<PollutionAverage> averageSubscriber = Wiring.createAverageSubscriber();
            Thresholds thresholds = Config.getThresholds();
            IAlertCooldownStore cooldownStore = Wiring.createCooldownStore(thresholds);
            IAlertSender alertSender = Wiring.createAlertSender();
            PollutionAlertService service = Wiring.createAlertService(
                    pollutionSubscriber, averageSubscriber, cooldownStore, alertSender, thresholds);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                health.markNotReady();
                service.close();
                health.close();
            }, "alert-service-shutdown"));
            service.start();
        } catch (RuntimeException e) {
            // a failed start must end the process, not leave it alive and never ready
            health.close();
            throw e;
        }
        health.markReady();
        logger.info("{} subscribed and running", Config.SERVICE_NAME);
    }
}
