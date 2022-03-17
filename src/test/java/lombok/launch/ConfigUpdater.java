package lombok.launch;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class ConfigUpdater
{
    //args[0] = path to AbstractLombokConfigMojo.java
    public static void main(final String[] args)
        throws Throwable
    {
        final List<Configuration> configurations = readLombokConfigurationKeys();
        final StringBuilder info = new StringBuilder();
        final String code = generateCode(configurations, info);
        final String result = updateAbstractMojoFile(args[0], code, info);
        System.out.println(result);
    }

    private static String updateAbstractMojoFile(final String file, final String code, final StringBuilder info)
        throws Throwable
    {
        final String contents = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        final String begin = "package lombok.maven;\n\n" + //
                "import org.apache.maven.plugin.AbstractMojo;\n" + //
                "import org.apache.maven.plugins.annotations.Parameter;\n\n" + //
                "//This class is generated by ConfigUpdater. DO NOT MODIFY.\n" + //
                "public abstract class AbstractLombokConfigMojo extends AbstractMojo\n" + //
                "{\n" + //
                "    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n" + //
                "    @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD)\n" + //
                "    static @interface Config {\n" + //
                "        String value() default \"\";\n" + //
                "        String list() default \"\";\n" + //
                "    }\n" + //
                "\n";
        final String newContents = begin + code + "}";
        if (!newContents.equals(contents)) {
            Files.write(Paths.get(file), newContents.getBytes(StandardCharsets.UTF_8));
            return info.toString() + "\nUpdated " + file;
        } else {
            return "No update needed.";
        }
    }

    private static String generateCode(final List<Configuration> configurations, final StringBuilder info)
    {
        final StringBuilder out = new StringBuilder();
        for (final Configuration config : configurations) {
            if (config.keyName.equals("config.stopBubbling") || (config.description != null && config.description.contains("Deprecated"))) {
                info.append("Skipped: ").append(config.keyName).append(" | ").append(config.description).append('\n');
                continue;
            }
            out.append("    /**\n");
            if (config.description != null && !config.description.isBlank()) {
                out.append("     * <p>").append(config.description).append("</p>\n     * ");
            }
            if (config.isList) {
                out.append("Value is a comma separated list. ");
            }
            out.append("Example:\n     * <pre>\n     * &lt;").append(config.fieldName).append("&gt;").append(config.example).append("&lt;/")
                    .append(config.fieldName).append("&gt;\n").append("     * </pre>\n     */\n").append("    @Config(");
            if (config.isList) {
                out.append("value=");
            }
            out.append('"').append(config.keyName).append('"');
            if (config.isList) {
                out.append(", list=\"true\"");
            }
            out.append(")\n").append("    @Parameter(property=\"");
            if (!config.keyName.startsWith("lombok.")) {
                out.append("lombok.");
            }
            out.append(config.keyName).append("\")\n    String ").append(config.fieldName).append(";\n\n");
        }
        return out.toString();
    }

    private static List<Configuration> readLombokConfigurationKeys()
        throws Throwable
    {
        //This must be done with reflection due to Lombok's ShadowClassLoader.
        final ClassLoader loader = new ShadowClassLoader(ConfigUpdater.class.getClassLoader(), "lombok", null, Arrays.<String>asList(),
                Arrays.asList("lombok.patcher.Symbols"));
        Thread.currentThread().setContextClassLoader(loader);

        final Class<?> loaderLoader = loader.loadClass("lombok.core.configuration.ConfigurationKeysLoader$LoaderLoader");
        final Method loadAll = loaderLoader.getMethod("loadAllConfigurationKeys");
        loadAll.invoke(null);

        final Class<?> configurationKey = loader.loadClass("lombok.core.configuration.ConfigurationKey");
        final Method registeredKeys = configurationKey.getMethod("registeredKeys");
        final Method getKeyName = configurationKey.getMethod("getKeyName");
        final Method getDescription = configurationKey.getMethod("getDescription");
        final Method getType = configurationKey.getMethod("getType");
        final Method isHidden = configurationKey.getMethod("isHidden");

        final Class<?> configurationDataType = loader.loadClass("lombok.core.configuration.ConfigurationDataType");
        final Method isList = configurationDataType.getMethod("isList");
        final Method getParser = configurationDataType.getDeclaredMethod("getParser");
        getParser.setAccessible(true);

        final Class<?> configurationValueParser = loader.loadClass("lombok.core.configuration.ConfigurationValueParser");
        final Method exampleValue = configurationValueParser.getMethod("exampleValue");
        exampleValue.setAccessible(true);

        final List<Configuration> keys = new ArrayList<>();
        for (final Object configKey : ((Map<?, ?>)registeredKeys.invoke(null)).values()) {
            if ((boolean)isHidden.invoke(configKey)) {
                continue;
            }
            final String name = (String)getKeyName.invoke(configKey);
            final String desc = (String)getDescription.invoke(configKey);
            final Object type = getType.invoke(configKey);
            final boolean list = (boolean)isList.invoke(type);
            final Object parser = getParser.invoke(type);
            final String example = ((String)exampleValue.invoke(parser)).replace("<", "[...").replace(">", "...]");
            keys.add(new Configuration(name, toFieldName(name), desc, example, list));
        }
        return keys;
    }

    private static String toFieldName(final String text)
    {
        final String[] words = text.replaceFirst("lombok\\.", "").split("\\.");
        final StringBuilder builder = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            builder.append(Character.toUpperCase(words[i].charAt(0)) + words[i].substring(1));
        }
        return builder.toString();
    }

    private static class Configuration
    {
        String keyName;
        String fieldName;
        boolean isList;
        String description;
        String example;

        private Configuration(final String keyName, final String fieldName, final String description, final String example,
                final boolean isList)
        {
            this.keyName = keyName;
            this.fieldName = fieldName;
            this.description = description;
            this.example = example;
            this.isList = isList;
        }
    }
}
