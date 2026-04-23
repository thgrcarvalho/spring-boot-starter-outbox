package io.github.thgrcarvalho.outbox;

import io.github.thgrcarvalho.outbox.internal.DefaultOutboxEventPublisher;
import io.github.thgrcarvalho.outbox.internal.InMemoryOutboxStore;
import io.github.thgrcarvalho.outbox.internal.JdbcOutboxStore;
import io.github.thgrcarvalho.outbox.internal.OutboxPoller;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Spring Boot auto-configuration for the transactional outbox starter. */
@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    OutboxProperties outboxProperties() {
        return new OutboxProperties();
    }

    // Redis store — preferred when Redis is configured
    @Configuration
    @ConditionalOnClass(name = "org.springframework.data.redis.connection.RedisConnectionFactory")
    static class RedisStoreConfiguration {
        @Bean
        @ConditionalOnMissingBean(OutboxStore.class)
        @ConditionalOnBean(RedisConnectionFactory.class)
        RedisOutboxStore redisOutboxStore(RedisConnectionFactory factory) {
            return new RedisOutboxStore(factory);
        }
    }

    // JDBC store — preferred when JdbcTemplate is available and Redis is not
    @Bean
    @ConditionalOnMissingBean(OutboxStore.class)
    @ConditionalOnClass(JdbcTemplate.class)
    @ConditionalOnBean(JdbcTemplate.class)
    JdbcOutboxStore jdbcOutboxStore(JdbcTemplate jdbc, OutboxProperties properties) {
        return new JdbcOutboxStore(jdbc, properties.getTableName());
    }

    // In-memory fallback — no persistence, suitable only for testing
    @Bean
    @ConditionalOnMissingBean(OutboxStore.class)
    InMemoryOutboxStore inMemoryOutboxStore() {
        return new InMemoryOutboxStore();
    }

    @Bean
    @ConditionalOnMissingBean(OutboxEventPublisher.class)
    DefaultOutboxEventPublisher outboxEventPublisher(OutboxStore store) {
        return new DefaultOutboxEventPublisher(store);
    }

    @Bean
    @ConditionalOnBean(OutboxPublisher.class)
    OutboxPoller outboxPoller(OutboxStore store,
                              OutboxPublisher publisher,
                              OutboxProperties properties) {
        return new OutboxPoller(store, publisher, properties);
    }

    // Micrometer metrics — wired only when MeterRegistry is present
    @Configuration
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {
        @Autowired
        void configureMetrics(OutboxPoller poller, MeterRegistry registry, OutboxStore store) {
            Counter published = Counter.builder("outbox.events.published")
                    .description("Outbox events successfully delivered")
                    .register(registry);
            Counter failed = Counter.builder("outbox.events.failed")
                    .description("Outbox event delivery failures (retriable)")
                    .register(registry);
            Counter deadLettered = Counter.builder("outbox.events.dead_lettered")
                    .description("Outbox events moved to FAILED after max attempts")
                    .register(registry);

            poller.setOnPublished(published::increment);
            poller.setOnFailed(failed::increment);
            poller.setOnDeadLettered(deadLettered::increment);
        }
    }
}
