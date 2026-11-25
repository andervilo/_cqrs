package com.cqrs.ordercommand.application.dashboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * When the dashboard pipeline is enabled, inserts the initial durable TIMER
 * request on startup so the five windows are calculated even before the first
 * temporal tick of the scheduler.
 */
@Component
@ConditionalOnProperty(prefix = "dashboard", name = "enabled", havingValue = "true")
public class DashboardStartup implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DashboardStartup.class);
    private final DashboardRefreshRequests requests;

    public DashboardStartup(DashboardRefreshRequests requests) {
        this.requests = requests;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Inserting initial dashboard TIMER request so the five windows are calculated on startup");
        requests.timer();
    }
}
