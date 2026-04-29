package org.springframework.data.redis.laboratory.util;

import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;

import java.io.InputStream;
import java.util.Properties;

public class RedisConfigUtils {

    private static final Properties prop = new Properties();

    // 静态代码块：类加载时自动加载配置文件
    static {
        try (InputStream input = RedisConfigUtils.class.getClassLoader()
                .getResourceAsStream("redis.properties")) {
            if (input != null) {
                prop.load(input);
            } else {
                System.err.println("[Warning] 未在 classpath 中找到 redis.properties，将使用默认配置。");
            }
        } catch (Exception e) {
            System.err.println("[Error] 读取 redis.properties 失败: " + e.getMessage());
        }
    }

    public static String getHost() {
        return prop.getProperty("redis.host", "127.0.0.1");
    }

    public static int getPort() {
        return Integer.parseInt(prop.getProperty("redis.port", "6379"));
    }

    public static int getDatabase() {
        return Integer.parseInt(prop.getProperty("redis.database", "0"));
    }

    public static String getPassword() {
        // 返回原始字符串，由调用方决定如何包装
        return prop.getProperty("redis.password", "");
    }
}
