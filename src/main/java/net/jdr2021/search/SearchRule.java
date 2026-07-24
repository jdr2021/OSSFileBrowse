package net.jdr2021.search;

import java.util.regex.Pattern;

/**
 * One compiled content-search rule. Rules use Java regular-expression syntax
 * and are immutable, so one loaded rule set can be reused by background scans.
 */
public final class SearchRule {
    private static final int FLAGS = Pattern.CASE_INSENSITIVE
            | Pattern.UNICODE_CASE | Pattern.MULTILINE;

    private final String group;
    private final String name;
    private final Pattern primary;
    private final Pattern secondary;
    private final String format;
    private final boolean custom;

    private SearchRule(String group,
                       String name,
                       Pattern primary,
                       Pattern secondary,
                       String format,
                       boolean custom) {
        this.group = group;
        this.name = name;
        this.primary = primary;
        this.secondary = secondary;
        this.format = format;
        this.custom = custom;
    }

    public static SearchRule configured(String group,
                                        String name,
                                        String primaryRegex,
                                        String secondaryRegex,
                                        String format) {
        Pattern primary = Pattern.compile(primaryRegex, FLAGS);
        Pattern secondary = isBlank(secondaryRegex)
                ? null : Pattern.compile(secondaryRegex, FLAGS);
        return new SearchRule(valueOr(group, "Rules"),
                valueOr(name, "未命名规则"), primary, secondary,
                valueOr(format, "{0}"), false);
    }

    public static SearchRule custom(String regex) {
        if (isBlank(regex)) {
            throw new IllegalArgumentException("自定义正则为空");
        }
        return new SearchRule("Custom", "自定义正则",
                Pattern.compile(regex, FLAGS), null, "{0}", true);
    }

    public String getGroup() {
        return group;
    }

    public String getName() {
        return name;
    }

    public Pattern getPrimary() {
        return primary;
    }

    public Pattern getSecondary() {
        return secondary;
    }

    public String getFormat() {
        return format;
    }

    public boolean isCustom() {
        return custom;
    }

    private static String valueOr(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
