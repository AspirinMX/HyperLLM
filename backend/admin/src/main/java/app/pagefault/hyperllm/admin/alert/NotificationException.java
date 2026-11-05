package app.pagefault.hyperllm.admin.alert;

/** A delivery failure, classified so the job knows whether a retry can ever succeed. */
public class NotificationException extends RuntimeException {

    private final String errorClass;
    private final boolean permanent;

    public NotificationException(String errorClass, String message, boolean permanent) {
        super(message);
        this.errorClass = errorClass;
        this.permanent = permanent;
    }

    public NotificationException(String errorClass, String message, boolean permanent, Throwable cause) {
        super(message, cause);
        this.errorClass = errorClass;
        this.permanent = permanent;
    }

    public String errorClass() {
        return errorClass;
    }

    public boolean permanent() {
        return permanent;
    }
}
