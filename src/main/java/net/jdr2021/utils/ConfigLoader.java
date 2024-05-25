package net.jdr2021.utils;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * @version 1.0
 * @Author jdr
 * @Date 2024-5-25 0:39
 * @注释
 */

public class ConfigLoader {

    private static final String CONFIG_FILE_PATH = "config.properties";
    private static Properties properties;

    static {
        properties = loadProperties();
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        try {
            // 通过 ClassLoader 加载资源文件
            InputStream inputStream = ConfigLoader.class.getClassLoader().getResourceAsStream(CONFIG_FILE_PATH);
            props.load(inputStream);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return props;
    }

    public static String getProperty(String key) {
        return properties.getProperty(key);
    }
}

