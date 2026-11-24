package ec.usfq.smartinventory.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** US-01: consulta paginada. Distingue fisico, reservado y disponible. Respuesta vacia = pagina sin items (200). */
@Service
public class InventoryQueryService {

    public record StockItem(String sku, String location, String lotCode, LocalDate expiresOn,
                            int physical, int reserved, int available) {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalItems) {
    }

    // Lista blanca: el nombre de ordenamiento jamas se concatena desde la entrada del usuario (anti-inyeccion)
    private static final Map<String, String> SORTABLE = Map.of(
            "sku", "sku", "location", "location", "lotCode", "lot_code",
            "expiresOn", "expires_on", "available", "(physical - reserved)");

    private final JdbcTemplate jdbc;

    public InventoryQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<StockItem> search(String sku, String location, String lot,
                                          int page, int size, String sort, boolean desc) {
        String column = SORTABLE.get(sort);
        if (column == null) {
            throw new ApiException(400, "INVALID_PARAMETER", "sort must be one of " + SORTABLE.keySet());
        }
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (sku != null) { where.append(" and sku = ?"); args.add(sku); }
        if (location != null) { where.append(" and location = ?"); args.add(location); }
        if (lot != null) { where.append(" and lot_code = ?"); args.add(lot); }

        Long total = jdbc.queryForObject("select count(*) from batch" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<StockItem> items = jdbc.query(
                "select sku, location, lot_code, expires_on, physical, reserved from batch" + where
                        + " order by " + column + (desc ? " desc" : " asc") + ", id limit ? offset ?",
                (rs, i) -> new StockItem(rs.getString("sku"), rs.getString("location"), rs.getString("lot_code"),
                        rs.getDate("expires_on").toLocalDate(), rs.getInt("physical"), rs.getInt("reserved"),
                        rs.getInt("physical") - rs.getInt("reserved")),
                pageArgs.toArray());
        return new PageResponse<>(items, page, size, total == null ? 0 : total);
    }
}
