package smoke;

final class OverrideResourceCleanups extends ParentResourceCleanups {

    public String describe() {
        return "child";
    }

    void useResource() {
        Resource resource = new Resource();
        try {
            resource.work();
        } finally {
            resource.close();
        }
    }

    static final class Resource implements java.io.Closeable {

        void work() {
            System.out.println("work");
        }

        @Override
        public void close() {
        }
    }
}

class ParentResourceCleanups {

    public String describe() {
        return "parent";
    }
}
