package com.cqrs.orderquery.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration(proxyBeanMethods=false)
public class DashboardClockConfiguration {
    @Bean @ConditionalOnMissingBean(Clock.class)
    Clock dashboardClock(){return Clock.systemUTC();}
}
