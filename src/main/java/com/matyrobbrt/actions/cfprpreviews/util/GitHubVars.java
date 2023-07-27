package com.matyrobbrt.actions.cfprpreviews.util;

import java.util.function.Function;
import java.util.function.Supplier;

public class GitHubVars {

    public static final Var<String> REPOSITORY = var(Type.GITHUB, "REPOSITORY");
    public static final Var<String> REPOSITORY_OWNER = var(Type.GITHUB, "REPOSITORY_OWNER");
    public static final Var<String> EVENT_PATH = var(Type.GITHUB, "EVENT_PATH");
    public static final Var<String> REF = var(Type.GITHUB, "REF");
    public static final Var<String> WORKFLOW = var(Type.GITHUB, "WORKFLOW");
    public static final Var<Long> RUN_NUMBER = new Var<>(Type.GITHUB, "RUN_NUMBER", Long::parseLong);

    public static final Var<String> GH_APP_KEY = var(Type.INPUT, "GH_APP_KEY");
    public static final Var<String> GH_APP_NAME = var(Type.INPUT, "GH_APP_NAME");
    public static final Var<String> PROJECT_NAME = var(Type.INPUT, "PROJECT_NAME");
    public static final Var<String> UPLOAD_DIR = var(Type.INPUT, "UPLOAD_DIR");

    private static Var<String> var(Type type, String key) {
        return new Var<>(type, key, Function.identity());
    }

    public static final class Var<Z> implements Supplier<Z> {
        private Z value;
        private final String key;
        private final Function<String, Z> mapper;
        private Var(Type type, String key, Function<String, Z> mapper) {
            this.key = type + "_" + key;
            this.mapper = mapper;
        }

        public Z get() {
            if (value == null) value = mapper.apply(System.getenv(key));
            return value;
        }
    }

    public enum Type {
        INPUT,
        GITHUB
    }

}
