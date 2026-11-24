package ec.usfq.smartinventory.messaging;

import ec.usfq.smartinventory.config.RabbitConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publicador del Outbox: lee pendientes (FOR UPDATE SKIP LOCKED permite varias instancias), publica con
 * confirmacion del broker y recien entonces marca published_at. Entrega al-menos-una-vez: por eso los
 * consumidores son idempotentes. Si el broker cae, las filas quedan pendientes y se publican al volver.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;

    public OutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit, TransactionTemplate tx, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.tx = tx;
        Gauge.builder("smartinventory.outbox.pending", this, OutboxPublisher::pendingCount)
                .description("Eventos en outbox sin publicar").register(metrics);
    }

    double pendingCount() {
        Long n = jdbc.queryForObject("select count(*) from outbox where published_at is null", Long.class);
        return n == null ? 0 : n;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-ms:500}")
    public void publishPending() {
        tx.executeWithoutResult(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select id, routing_key, payload::text as payload, correlation_id
                    from outbox where published_at is null
                    order by occurred_at limit 50 for update skip locked""");
            for (Map<String, Object> row : rows) {
                try {
                    publish(row);
                    jdbc.update("update outbox set published_at = now() where id = ?", row.get("id"));
                } catch (Exception e) {
                    log.warn("outbox publish failed for event {}; will retry on next poll", row.get("id"), e);
                    return;   // conserva el orden: no se adelantan eventos posteriores
                }
            }
        });
    }

    private void publish(Map<String, Object> row) {
        rabbit.invoke(ops -> {
            ops.convertAndSend(RabbitConfig.EVENTS_EXCHANGE, (String) row.get("routing_key"),
                    (Object) row.get("payload"), message -> {
                        var p = message.getMessageProperties();
                        p.setContentType("application/json");
                        p.setMessageId(String.valueOf(row.get("id")));
                        p.setCorrelationId((String) row.get("correlation_id"));
                        return message;
                    });
            ops.waitForConfirmsOrDie(5_000);
            return true;
        });
    }
}
