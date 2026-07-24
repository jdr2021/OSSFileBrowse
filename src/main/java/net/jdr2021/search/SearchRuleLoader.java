package net.jdr2021.search;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Loads enabled rules from the bundled {@code Rules.yml}. */
public final class SearchRuleLoader {
    public static final String DEFAULT_RESOURCE = "/Rules.yml";

    private SearchRuleLoader() {
    }

    public static Result loadDefault() throws IOException {
        InputStream input = SearchRuleLoader.class.getResourceAsStream(
                DEFAULT_RESOURCE);
        if (input == null) {
            throw new IOException("Rules.yml 资源未找到");
        }
        return load(input);
    }

    @SuppressWarnings("unchecked")
    public static Result load(InputStream input) throws IOException {
        if (input == null) {
            throw new IOException("规则输入流为空");
        }
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(2 * 1024 * 1024);
        Object loaded;
        try (InputStream source = input) {
            loaded = new Yaml(new SafeConstructor(options)).load(source);
        } catch (RuntimeException failure) {
            throw new IOException("Rules.yml 解析失败", failure);
        }
        if (!(loaded instanceof Map)) {
            throw new IOException("Rules.yml 顶层结构应为映射");
        }
        Object groupsValue = ((Map<?, ?>) loaded).get("rules");
        if (!(groupsValue instanceof Iterable)) {
            throw new IOException("Rules.yml 缺少 rules 列表");
        }

        List<SearchRule> rules = new ArrayList<SearchRule>();
        List<String> warnings = new ArrayList<String>();
        for (Object groupValue : (Iterable<Object>) groupsValue) {
            if (!(groupValue instanceof Map)) {
                continue;
            }
            Map<Object, Object> groupMap = (Map<Object, Object>) groupValue;
            String group = stringValue(groupMap.get("group"));
            Object ruleValues = groupMap.get("rule");
            if (!(ruleValues instanceof Iterable)) {
                continue;
            }
            for (Object ruleValue : (Iterable<Object>) ruleValues) {
                if (!(ruleValue instanceof Map)) {
                    continue;
                }
                Map<Object, Object> ruleMap =
                        (Map<Object, Object>) ruleValue;
                if (!booleanValue(ruleMap.get("loaded"), true)) {
                    continue;
                }
                String name = stringValue(ruleMap.get("name"));
                String primary = stringValue(ruleMap.get("f_regex"));
                String secondary = stringValue(ruleMap.get("s_regex"));
                String format = stringValue(ruleMap.get("format"));
                if (primary.trim().isEmpty()) {
                    warnings.add(display(group, name)
                            + "：f_regex 为空");
                    continue;
                }
                try {
                    rules.add(SearchRule.configured(group, name, primary,
                            secondary, format));
                } catch (RuntimeException invalidRegex) {
                    warnings.add(display(group, name) + "："
                            + invalidRegex.getMessage());
                }
            }
        }
        return new Result(rules, warnings);
    }

    private static String display(String group, String name) {
        return (group == null ? "" : group) + "/"
                + (name == null ? "" : name);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean booleanValue(Object value,
                                        boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return value instanceof Boolean
                ? (Boolean) value
                : Boolean.parseBoolean(String.valueOf(value));
    }

    public static final class Result {
        private final List<SearchRule> rules;
        private final List<String> warnings;

        private Result(List<SearchRule> rules, List<String> warnings) {
            this.rules = Collections.unmodifiableList(
                    new ArrayList<SearchRule>(rules));
            this.warnings = Collections.unmodifiableList(
                    new ArrayList<String>(warnings));
        }

        public List<SearchRule> getRules() {
            return rules;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }
}
