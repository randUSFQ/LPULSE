package ec.usfq.smartinventory.config;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security")
public record SecurityProperties(List<ApiKey> keys) {

    public record ApiKey(String key, String client, Set<String> scopes) {
        // Evita que la clave aparezca en logs si alguien imprime el objeto
        @Override
        public String toString() {
            return "ApiKey[client=" + client + ", scopes=" + scopes + "]";
        }
    }
}
