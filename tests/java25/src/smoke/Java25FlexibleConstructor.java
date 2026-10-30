package smoke;

// Java 25 (JEP 513): constructor validation before an explicit super() call.
public final class Java25FlexibleConstructor {
    public Java25FlexibleConstructor(Object candidate) {
        if (candidate instanceof String) {
            String text = (String) candidate;
            if (text.isBlank()) {
                throw new IllegalArgumentException("Blank text");
            }
        }

        super();
    }
}
