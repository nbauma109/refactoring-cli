package smoke;

final class ComplementarySystemProperties {

    String fileSeparator() {
        return System.getProperty("file.separator");
    }

    String pathSeparator() {
        return System.getProperty("path.separator");
    }

    String lineSeparator() {
        return System.getProperty("line.separator");
    }

    String fileEncoding() {
        return System.getProperty("file.encoding");
    }

    boolean featureEnabled() {
        return Boolean.parseBoolean(System.getProperty("com.example.enabled"));
    }
}
