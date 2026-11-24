package ec.usfq.smartinventory.config;

import java.util.Map;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia: exchange topic -> colas por consumidor -> DLX -> DLQ.
 * Un mensaje rechazado tras agotar reintentos (spring.rabbitmq.listener.simple.retry) va a inventory.dlq.
 */
@Configuration
public class RabbitConfig {

    public static final String EVENTS_EXCHANGE = "smartinventory.events";
    public static final String DLX = "inventory.dlx";
    public static final String DLQ = "inventory.dlq";
    public static final String DASHBOARD_Q = "dashboard.q";
    public static final String REPLENISHMENT_Q = "replenishment.q";
    public static final String STOCK_RESERVED = "inventory.stock-reserved";
    public static final String SUGGESTION_OPENED = "replenishment.suggestion-opened";
    public static final String SUGGESTION_APPROVED = "replenishment.suggestion-approved";

    private static final long DLQ_RETENTION_MS = 7L * 24 * 3600 * 1000;

    @Bean TopicExchange eventsExchange() { return new TopicExchange(EVENTS_EXCHANGE, true, false); }

    @Bean DirectExchange deadLetterExchange() { return new DirectExchange(DLX, true, false); }

    @Bean Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).withArguments(Map.of("x-message-ttl", DLQ_RETENTION_MS)).build();
    }

    @Bean Queue dashboardQueue() {
        return QueueBuilder.durable(DASHBOARD_Q).deadLetterExchange(DLX).deadLetterRoutingKey("dead").build();
    }

    @Bean Queue replenishmentQueue() {
        return QueueBuilder.durable(REPLENISHMENT_Q).deadLetterExchange(DLX).deadLetterRoutingKey("dead").build();
    }

    @Bean Binding dlqBinding() { return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with("dead"); }

    @Bean Binding dashboardBinding() {
        // Cada consumidor tiene su propia cola: uno lento o caido no afecta al otro
        return BindingBuilder.bind(dashboardQueue()).to(eventsExchange()).with(STOCK_RESERVED);
    }

    @Bean Binding replenishmentBinding() {
        return BindingBuilder.bind(replenishmentQueue()).to(eventsExchange()).with(STOCK_RESERVED);
    }
}
