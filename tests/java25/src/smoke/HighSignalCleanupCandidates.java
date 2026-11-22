package smoke;

public final class HighSignalCleanupCandidates {

    @SuppressWarnings("unused")
    public int usedMethod() {
        return 42;
    }

    public boolean repeatedRegularExpression(String first, String second) {
        String expression = "[a-z]+";
        return first.matches(expression) && second.matches(expression);
    }

    private class StatelessInner {
        int doubleValue(int value) {
            return value * 2;
        }
    }

    public int calculate(int value) {
        return new StatelessInner().doubleValue(value);
    }
}
