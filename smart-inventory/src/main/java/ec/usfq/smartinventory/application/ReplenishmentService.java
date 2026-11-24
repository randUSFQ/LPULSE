package ec.usfq.smartinventory.application;

import ec.usfq.smartinventory.config.RabbitConfig;
import ec.usfq.smartinventory.messaging.OutboxWriter;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReplenishmentService {

    public record SuggestionView(UUID id, String sku, String supplier, int suggestedQty, String status,
                                 OffsetDateTime createdAt, String approvedBy, OffsetDateTime approvedAt) {
    }

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;

    public ReplenishmentService(JdbcTemplate jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /** No muta stock: solo lectura (GET seguro). */
    @Transactional(readOnly = true)
    public List<SuggestionView> list(String status, String sku, String supplier, int limit) {
        StringBuilder sql = new StringBuilder("""
                select id, sku, supplier, suggested_qty, status, created_at, approved_by, approved_at
                from replenishment_suggestion where 1 = 1""");
        List<Object> args = new ArrayList<>();
        if (status != null) { sql.append(" and status = ?"); args.add(status); }
        if (sku != null) { sql.append(" and sku = ?"); args.add(sku); }
        if (supplier != null) { sql.append(" and supplier = ?"); args.add(supplier); }
        sql.append(" order by created_at desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> view(rs), args.toArray());
    }

    /** Transicion valida unica: OPEN -> APPROVED. El UPDATE condicional hace la transicion atomica. */
    @Transactional
    public SuggestionView approve(UUID id, String approver, String correlationId) {
        int updated = jdbc.update("""
                update replenishment_suggestion set status = 'APPROVED', approved_by = ?, approved_at = now()
                where id = ? and status = 'OPEN'""", approver, id);
        if (updated == 0) {
            var existing = jdbc.query("select status from replenishment_suggestion where id = ?",
                    (rs, i) -> rs.getString(1), id);
            if (existing.isEmpty()) {
                throw ApiException.notFound("Suggestion " + id + " not found");
            }
            throw ApiException.conflict("INVALID_TRANSITION",
                    "Suggestion is " + existing.get(0) + "; only OPEN can be approved");
        }
        var view = jdbc.queryForObject("""
                select id, sku, supplier, suggested_qty, status, created_at, approved_by, approved_at
                from replenishment_suggestion where id = ?""", (rs, i) -> view(rs), id);
        outbox.append(RabbitConfig.SUGGESTION_APPROVED, correlationId,
                Map.of("suggestionId", id.toString(), "sku", view.sku(), "approvedBy", approver));
        return view;
    }

    private static SuggestionView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SuggestionView(rs.getObject("id", UUID.class), rs.getString("sku"), rs.getString("supplier"),
                rs.getInt("suggested_qty"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("approved_by"),
                rs.getObject("approved_at", OffsetDateTime.class));
    }
}
