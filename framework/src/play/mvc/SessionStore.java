package play.mvc;

/**
 * Implementations of session storage mechanisms.
 */
public interface SessionStore {
    void save(Context context);
    Scope.Session restore(Context context);
}
