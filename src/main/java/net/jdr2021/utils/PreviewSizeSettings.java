package net.jdr2021.utils;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the optional global file-preview size limit from config.properties.
 */
public final class PreviewSizeSettings {
    public static final String CONFIG_PROPERTY = "preview.max.size";

    private static final long MEBIBYTE = 1024L * 1024L;
    private static final long GIBIBYTE = 1024L * 1024L * 1024L;
    private static final Pattern VALUE =
            Pattern.compile("^([1-9][0-9]*)([mMgG])$");

    private PreviewSizeSettings() {
    }

    public static String configuredValue() {
        String value = ConfigLoader.getProperty(CONFIG_PROPERTY);
        return value == null ? "" : value.trim();
    }

    /**
     * Empty means that this setting adds no file-size restriction.
     */
    public static OptionalLong configuredLimitBytes() {
        return parse(configuredValue());
    }

    public static OptionalLong parse(String requestedValue) {
        String value = requestedValue == null ? "" : requestedValue.trim();
        if (value.isEmpty()) {
            return OptionalLong.empty();
        }
        Matcher matcher = VALUE.matcher(value);
        if (!matcher.matches()) {
            throw invalidValue(value);
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            long multiplier = "G".equals(
                    matcher.group(2).toUpperCase(Locale.ROOT))
                    ? GIBIBYTE : MEBIBYTE;
            return OptionalLong.of(Math.multiplyExact(amount, multiplier));
        } catch (ArithmeticException | NumberFormatException overflow) {
            throw invalidValue(value);
        }
    }

    public static boolean exceedsLimit(long fileSize) {
        OptionalLong limit = configuredLimitBytes();
        return fileSize >= 0 && limit.isPresent()
                && fileSize > limit.getAsLong();
    }

    public static String description() {
        String value = configuredValue();
        if (value.isEmpty()) {
            return "空（不限制）";
        }
        try {
            long bytes = configuredLimitBytes().getAsLong();
            return value.toUpperCase(Locale.ROOT) + "（"
                    + bytes + " 字节）";
        } catch (IllegalArgumentException invalid) {
            return "配置错误：" + invalid.getMessage();
        }
    }

    private static IllegalArgumentException invalidValue(String value) {
        return new IllegalArgumentException(CONFIG_PROPERTY
                + " 的值格式错误：" + value
                + "；请使用数字加 M 或 G，例如 100M、2G；留空表示不限制");
    }
}
