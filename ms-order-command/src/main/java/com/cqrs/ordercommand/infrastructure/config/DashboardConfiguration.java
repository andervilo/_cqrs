package com.cqrs.ordercommand.infrastructure.config;

import com.cqrs.ordercommand.application.dashboard.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(DashboardProperties.class)
public class DashboardConfiguration {
    @Bean @ConditionalOnMissingBean(Clock.class)
    Clock dashboardClock(){return Clock.systemUTC();}

    @Bean
    MeterBinder dashboardMetrics(JdbcTemplate jdbc) {
        return registry -> {
            Gauge.builder("dashboard.refresh.pending.requests",jdbc,
                    j->j.queryForObject("SELECT count(*) FROM order_command.dashboard_refresh_request WHERE processed_at IS NULL",Double.class)).register(registry);
            for(var period:DashboardPeriod.values()) {
                Gauge.builder("dashboard.refresh.age.seconds",jdbc,j->j.queryForObject("""
                        SELECT coalesce(extract(epoch FROM clock_timestamp()-last_source_at),-1)
                        FROM order_command.dashboard_refresh_control WHERE dashboard_id=?
                        """,Double.class,period.id())).tag("period",period.name()).register(registry);
            }
        };
    }

    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(prefix="dashboard",name="enabled",havingValue="true")
    @EnableScheduling
    static class Scheduling {
        @Bean(name="taskScheduler") @ConditionalOnMissingBean(name="taskScheduler")
        ThreadPoolTaskScheduler applicationTaskScheduler(){
            var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("scheduled-");return scheduler;
        }
        @Bean
        ThreadPoolTaskScheduler dashboardTaskScheduler(){
            var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(3);
            scheduler.setThreadNamePrefix("dashboard-");scheduler.setWaitForTasksToCompleteOnShutdown(true);
            scheduler.setAwaitTerminationSeconds(15);return scheduler;
        }
        @Bean DashboardTasks dashboardTasks(DashboardRefreshRequests requests,DashboardRefreshDispatcher dispatcher,
                DashboardRefreshWorker worker,DashboardFailureStore failures,MeterRegistry metrics) {
            return new DashboardTasks(requests,dispatcher,worker,failures,metrics);
        }
    }
    static class DashboardTasks {
        private static final Logger log=LoggerFactory.getLogger(DashboardTasks.class);
        private final DashboardRefreshRequests requests; private final DashboardRefreshDispatcher dispatcher;
        private final DashboardRefreshWorker worker; private final DashboardFailureStore failures; private final MeterRegistry metrics;
        DashboardTasks(DashboardRefreshRequests requests,DashboardRefreshDispatcher dispatcher,DashboardRefreshWorker worker,
                DashboardFailureStore failures,MeterRegistry metrics){
            this.requests=requests;this.dispatcher=dispatcher;this.worker=worker;this.failures=failures;this.metrics=metrics;
        }
        @Scheduled(scheduler="dashboardTaskScheduler",fixedDelayString="${dashboard.dispatch-interval-ms:1000}",initialDelayString="${dashboard.initial-delay-ms:5000}")
        public void dispatch(){try{metrics.counter("dashboard.refresh.dispatched").increment(dispatcher.dispatch());}
            catch(RuntimeException e){log.warn("Dashboard dispatch failed type={}",e.getClass().getSimpleName());}}
        @Scheduled(scheduler="dashboardTaskScheduler",fixedDelayString="${dashboard.worker-interval-ms:2000}",initialDelayString="${dashboard.initial-delay-ms:5000}")
        public void compute(){
            try { if(worker.processOne())metrics.counter("dashboard.refresh.calculated").increment(); }
            catch(DashboardRefreshWorker.CalculationFailure e){failures.failed(e);log.warn("Dashboard calculation failed id={} generation={} type={}",e.id(),e.generation(),e.getCause().getClass().getSimpleName());}
            catch(RuntimeException e){log.warn("Dashboard worker failed type={}",e.getClass().getSimpleName());}
        }
        @Scheduled(scheduler="dashboardTaskScheduler",fixedDelayString="${dashboard.temporal-interval-ms:60000}",initialDelayString="${dashboard.temporal-interval-ms:60000}")
        public void temporal(){requests.timer();}
    }
}
