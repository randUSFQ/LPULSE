package ec.usfq.smartinventory.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Formato de error unico (application/problem+json, RFC 9457): code, detail, requestId, errors[]. */
@Component
public class ProblemWriter {

    public static final String PROBLEM_JSON = "application/problem+json";

    private final ObjectMapper mapper;

    public ProblemWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Map<String, Object> body(int status, String code, String detail, List<Map<String, String>> errors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "https://smartinventory.usfq.edu.ec/problems/" + code.toLowerCase().replace('_', '-'));
        m.put("title", HttpStatus.valueOf(status).getReasonPhrase());
        m.put("status", status);
        m.put("code", code);
        m.put("detail", detail);
        m.put("requestId", MDC.get(CorrelationIdFilter.MDC_KEY));
        if (errors != null && !errors.isEmpty()) {
            m.put("errors", errors);
        }
        return m;
    }

    /** Para filtros, que no pasan por @RestControllerAdvice. */
    public void write(HttpServletResponse resp, int status, String code, String detail) throws IOException {
        resp.setStatus(status);
        resp.setContentType(PROBLEM_JSON);
        mapper.writeValue(resp.getOutputStream(), body(status, code, detail, null));
    }
}
