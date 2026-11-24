package ec.usfq.smartinventory.application;

/** Error de aplicacion con codigo HTTP y codigo de negocio estable (para quien integra el servicio). */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }

    public String code() { return code; }

    public static ApiException notFound(String message) { return new ApiException(404, "NOT_FOUND", message); }

    public static ApiException conflict(String code, String message) { return new ApiException(409, code, message); }
}
