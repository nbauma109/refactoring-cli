package smoke;

public final class UnusedPrivateMemberCandidates {

    private int unusedCounter = 7;
    private int activeCounter = 11;

    private String unusedDescription() {
        return "unused";
    }

    private int usedDescription() {
        return activeCounter;
    }

    public int activeValue() {
        return usedDescription();
    }
}
