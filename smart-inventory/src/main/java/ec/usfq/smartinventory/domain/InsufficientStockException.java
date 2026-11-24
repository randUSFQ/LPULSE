package ec.usfq.smartinventory.domain;

public class InsufficientStockException extends RuntimeException {

    private final int requested;
    private final int available;

    public InsufficientStockException(int requested, int available) {
        super("Requested " + requested + " but only " + available + " assignable");
        this.requested = requested;
        this.available = available;
    }

    public int requested() {
        return requested;
    }

    public int available() {
        return available;
    }
}
