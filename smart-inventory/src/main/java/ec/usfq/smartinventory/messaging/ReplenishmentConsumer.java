package ec.usfq.smartinventory.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import ec.usfq.smartinventory.config.RabbitConfig;
import ec.usfq.smartinventory.domain.ReplenishmentRule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** US-03: ante un cambio de stock evalua la regla y abre (a lo sumo una) sugerencia por SKU. */
@Component
public class ReplenishmentConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentConsumer.class);
    static final String NAME = "replenishment";

    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final ProcessedEvents processed;
    private final ReplenishmentRule rule;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ReplenishmentConsumer(ObjectMapper mapper, JdbcTemplate jdbc, ProcessedEvents processed,
                                 ReplenishmentRule rule, OutboxWriter outbox, Clock clock) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.processed = processed;
        this.rule = rule;
        this.outbox = outbox;
        this.clock = clock;
    }

    @RabbitListener(queues = RabbitConfig.REPLENISHMENT_Q)
    @Transactional
    public void onMessage(Message message) throws IOException {
        EventEnvelope e = mapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), EventEnvelope.class);
        MDC.put("requestId", e.correlationId());
        try {
            if (!processed.firstTime(NAME, e.eventId())) {
                log.info("duplicate event {} ignored", e.eventId());
                return;
            }
            String sku = (String) e.payload().get("sku");
            var cfg = jdbc.queryForList(
                    "select reorder_point, target_level, supplier from reorder_config where sku = ?", sku);
            if (cfg.isEmpty()) {
                return;   // SKU sin politica de reposicion
            }
            Integer available = jdbc.queryForObject(
                    "select coalesce(sum(physical - reserved), 0) from batch where sku = ? and expires_on >= ?",
                    Integer.class, sku, java.sql.Date.valueOf(LocalDate.now(clock)));
            int reorderPoint = ((Number) cfg.get(0).get("reorder_point")).intValue();
            int target = ((Number) cfg.get(0).get("target_level")).intValue();

            rule.evaluate(sku, available == null ? 0 : available, reorderPoint, target).ifPresent(s -> {
                UUID id = UUID.randomUUID();
                int inserted = jdbc.update("""
                        insert into replenishment_suggestion (id, sku, supplier, suggested_qty)
                        values (?, ?, ?, ?)
                        on conflict (sku) where status = 'OPEN' do nothing""",
                        id, sku, cfg.get(0).get("supplier"), s.quantity());
                if (inserted == 1) {
                    outbox.append(RabbitConfig.SUGGESTION_OPENED, e.correlationId(),
                            Map.of("suggestionId", id.toString(), "sku", sku, "suggestedQty", s.quantity()));
                    log.info("replenishment suggestion opened for sku {}", sku);
                }
            });
        } finally {
            MDC.remove("requestId");
        }
    }
}
