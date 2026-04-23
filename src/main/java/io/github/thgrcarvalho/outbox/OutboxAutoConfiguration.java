package io.github.thgrcarvalho.outbox;

import io.github.thgrcarvalho.outbox.internal.DefaultOutboxEventPublisher;
import io.github.thgrcarvalho.outbox.internal.InMemoryOutboxStore;
import io.github.thgrcarvalho.outbox.internal.JdbcOutboxStore;
import io.github.thgrcarvalho.outbox.internal.OutboxPoller;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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

    // JDBC store — preferred when JdbcTemplate is available
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
}
