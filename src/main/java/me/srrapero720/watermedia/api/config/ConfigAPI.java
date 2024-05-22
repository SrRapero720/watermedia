package me.srrapero720.watermedia.api.config;

import me.srrapero720.watermedia.WaterMedia;
import me.srrapero720.watermedia.api.WaterInternalAPI;
import me.srrapero720.watermedia.api.config.values.ConfigField;
import me.srrapero720.watermedia.api.config.values.RangeOf;
import me.srrapero720.watermedia.api.config.values.WaterConfigFile;
import me.srrapero720.watermedia.loaders.ILoader;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class ConfigAPI extends WaterInternalAPI {

    private static WaterConfig config = new WaterConfig();

    private static Properties properties;
    private static File configFile;

    private static void loadConfiguration(Path configFilePath, Object configInstance) {
        configFile = new File(configFilePath.toUri());

        properties = new Properties();

        try {
            if (!configFile.exists()) {
                configFile.getParentFile().mkdirs();
                configFile.createNewFile();
                saveConfiguration(configInstance);
            }

            try (InputStream inputStream = Files.newInputStream(configFile.toPath())) {
                properties.load(inputStream);
            }

            if (synchronizeFromProperties(configInstance)) {
                WaterMedia.LOGGER.info("Configuration loaded correctly.");
            } else {
                WaterMedia.LOGGER.error("There was a problem syncing the configuration.");
            }
        } catch (IOException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    public static void saveConfiguration(Object configInstance) {
        try (OutputStream outputStream = Files.newOutputStream(configFile.toPath())) {
            synchronizeToProperties(configInstance);
            properties.store(outputStream, "WaterMedia Configuration");
        } catch (IOException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    private static boolean synchronizeFromProperties(Object instance) throws IllegalAccessException {
        Class<?> clazz = instance.getClass();
        if (clazz.isAnnotationPresent(WaterConfigFile.class)) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(ConfigField.class)) {
                    String key = field.getName();
                    String value = properties.getProperty(key);

                    if (value != null) {
                        field.setAccessible(true);
                        if (!setFieldValue(field, value, instance)) return false;
                    }
                }
            }
        }
        return true;
    }

    private static void synchronizeToProperties(Object instance) throws IllegalAccessException {
        Class<?> clazz = instance.getClass();
        if (clazz.isAnnotationPresent(WaterConfigFile.class)) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(ConfigField.class)) {
                    String key = field.getName();
                    field.setAccessible(true);
                    properties.setProperty(key, field.get(instance).toString());
                }
            }
        }
    }

    private static boolean setFieldValue(Field field, String value, Object instance) {
        Class<?> type = field.getType();
        try {
            if (type == int.class) {
                int intValue = Integer.parseInt(value);
                if (field.isAnnotationPresent(RangeOf.class)) {
                    RangeOf range = field.getAnnotation(RangeOf.class);
                    if (!validateRange(intValue, range)) {
                        return false;
                    }
                }
                field.setInt(instance, intValue);
            } else if (type == long.class) {
                long longValue = Long.parseLong(value);
                field.setLong(instance, longValue);
            } else if (type == float.class) {
                float floatValue = Float.parseFloat(value);
                field.setFloat(instance, floatValue);
            } else if (type == double.class) {
                double doubleValue = Double.parseDouble(value);
                field.setDouble(instance, doubleValue);
            } else if (type == String.class) {
                field.set(instance, value);
            } else if (type.isEnum()) {
                Object enumValue = Enum.valueOf((Class<Enum>) type, value);
                field.set(instance, enumValue);
            } else if (type == boolean.class) {
                boolean booleanValue = Boolean.parseBoolean(value);
                field.setBoolean(instance, booleanValue);
            }
        } catch (IllegalArgumentException | NullPointerException | IllegalAccessException ignored) {
            return false;
        }
        return true;
    }

    private static boolean validateRange(int value, RangeOf range) {
        return value >= range.min() && value <= range.max();
    }

    @Override
    public Priority priority() {
        return Priority.HIGHEST;
    }

    @Override
    public boolean prepare(ILoader bootCore) throws Exception {
        return true;
    }

    @Override
    public void start(ILoader bootCore) throws Exception {
        loadConfiguration(bootCore.processDir().resolve("config/watermedia.wt"), config);
    }

    @Override
    public void release() {
//        saveConfiguration(config);
    }
}
