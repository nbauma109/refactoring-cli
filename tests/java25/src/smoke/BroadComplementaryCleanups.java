package smoke;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class BroadComplementaryCleanups {

    private int assignedOnlyOnce = 42;
    private int configuredValue;

    public BroadComplementaryCleanups(int configuredValue) {
        this.configuredValue = configuredValue;
    }

    public int configuredValue() {
        return configuredValue;
    }
    private StringBuffer accumulator = new StringBuffer();

    public String obsoleteStringConstructor() {
        return new String("constant");
    }

    public boolean blankString(String value) {
        return value.strip().isEmpty();
    }

    public String versionProperty() {
        return System.getProperty("java.runtime.version");
    }

    public String specificationVersionProperty() {
        return System.getProperty("java.specification.version");
    }

    public int assignmentExtraction(boolean enabled) {
        int answer;
        if (enabled) {
            answer = 10;
        } else {
            answer = 20;
        }
        return answer;
    }

    public boolean pullUpAssignment(List<String> values) {
        boolean removed;
        if (removed = values.remove("candidate")) {
            return removed;
        }
        return false;
    }

    public int incrementExtraction() {
        int counter = 1;
        boolean positive = ++counter > 0;
        return positive ? counter : -counter;
    }

    @SuppressWarnings("unused")
    public String unnecessarySuppression() {
        return "used";
    }

    public StringBuffer stringBuffer() {
        StringBuffer builder = new StringBuffer("hello");
        builder.append(" world");
        return builder;
    }

    public int[] arrayInitializer() {
        int[] numbers = new int[] {1, 2, 3};
        return numbers;
    }

    private static void acceptAll(String... names) {
        for (String name : names) {
            System.out.println(name);
        }
    }

    public void redundantVarargsArray() {
        acceptAll(new String[] {"alpha", "beta"});
    }

    public void addCollectionContents(List<String> destination, List<String> source) {
        for (String element : source) {
            destination.add(element);
        }
    }

    public boolean repeatedRegex(String text, List<String> lines) {
        for (String line : lines) {
            if (line.matches("[a-z]+") && text.matches("[a-z]+")) {
                return true;
            }
        }
        return false;
    }

    public List<String> redundantTypeArguments() {
        List<String> values = new ArrayList<String>();
        values.add("element");
        return values;
    }

    public int unnecessaryReturn() {
        int result = 4;
        return result;
    }

    public void redundantVoidReturn() {
        System.out.println("done");
        return;
    }

    public StringBuilder locallyConstructedBuilder() {
        StringBuilder builder = new StringBuilder();
        builder.append("prefix");
        builder.append("suffix");
        return builder;
    }

    public int duplicateBranches(boolean condition, boolean other) {
        if (condition) {
            if (other) {
                return 10;
            }
        } else {
            if (other) {
                return 10;
            }
        }
        return 20;
    }

    public int reducibleIndentation(boolean flag) {
        if (flag) {
            return 1;
        } else {
            return 2;
        }
    }

    public int replaceWithTernary(boolean flag) {
        if (flag) {
            return 1;
        }
        return 2;
    }

    public String unnecessaryNonLocalizedTag() {
        return "text"; //$NON-NLS-1$
    }

    public static class StaticCandidate {
        private final String value = "candidate";

        String value() {
            return value;
        }
    }

    class NonStaticCandidate {
        String name() {
            return "does not use the outer class";
        }
    }

    interface PublicInterface {
        public abstract void execute();
    }

    public static Map<String, Integer> hashMap() {
        return new java.util.HashMap<String, Integer>();
    }

    public static Collection<String> list() {
        return new ArrayList<>();
    }
}
