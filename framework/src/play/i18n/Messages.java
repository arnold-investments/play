package play.i18n;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import play.Play;
import play.mvc.Context;

/**
 * I18n Helper
 * <p>
 * translation are defined as properties in /conf/messages.<em>locale</em> files with locale being the i18n country code
 * fr, en, fr_FR
 * 
 * <pre>
 * # /conf/messages.fr
 * hello=Bonjour, %s !
 * </pre>
 * 
 * <code>
 * Messages.get( "hello", "World"); // =&gt; "Bonjour, World !"
 * </code>
 * 
 */
public class Messages {

    private static final Object[] NO_ARGS = new Object[] { null };

    public static Properties defaults = new Properties();

    public static final Map<String, Properties> locales = new HashMap<>();

    private static final Pattern recursive = Pattern.compile("&\\{(.*?)\\}");

    private final Context context;

    public Messages(Context context) {
        this.context = context;
    }

    /**
     * Given a message code, translate it using current locale. If there is no message in the current locale for the
     * given key, the key is returned.
     * 
     * @param key
     *            the message code
     * @param args
     *            optional message format arguments
     * @return translated message
     */
    public static String get(String locale, Object key, Object... args) {
        return getMessage(locale, key, args);
    }

    public String get(Object key, Object... args) {
        return getMessage(Lang.get(context), key, args);
    }

    /**
     * Return several messages for a locale
     * 
     * @param locale
     *            the locale code, e.g. fr, fr_FR
     * @param keys
     *            the keys to get messages from. Wildcards can be used at the end: {'title', 'login.*'}
     * @return messages as a {@link java.util.Properties java.util.Properties}
     */
    public static Properties find(String locale, Set<String> keys) {
        Properties result = new Properties();
        Properties all = all(locale);
        // Expand the set for wildcards
        Set<String> wildcards = new HashSet<>();
        for (String key : keys) {
            if (key.endsWith("*"))
                wildcards.add(key);
        }
        for (String key : wildcards) {
            keys.remove(key);
            String start = key.substring(0, key.length() - 1);
            for (Object key2 : all.keySet()) {
                if (((String) key2).startsWith(start)) {
                    keys.add((String) key2);
                }
            }
        }
        // Build the result
        for (Object key : all.keySet()) {
            if (keys.contains(key)) {
                result.put(key, all.get(key));
            }
        }
        return result;
    }

    public static String getMessage(String localeStr, Object key, Object... args) {
        // Check if there is a plugin that handles translation
        String message = Play.pluginCollection.getMessage(localeStr, key, args);
        if (message != null) {
            return message;
        }

        if (key == null) {
            return "";
        }
        String value = null;
        if (locales.containsKey(localeStr)) {
            value = locales.get(localeStr).getProperty(key.toString());
        }
        if (value == null && localeStr != null && localeStr.length() == 5 && locales.containsKey(localeStr.substring(0, 2))) {
            value = locales.get(localeStr.substring(0, 2)).getProperty(key.toString());
        }
        if (value == null && defaults != null) {
            value = defaults.getProperty(key.toString());
        }
        if (value == null) {
            value = key.toString();
        }
        Locale l = Lang.getLocaleOrDefault(localeStr);
        return formatString(l, value, args);
    }

    public static String formatString(Locale locale, String value, Object... args) {
        String message = String.format(locale, value, filterForStringFormat(args));

        Matcher matcher = recursive.matcher(message);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, get(locale.toString(), matcher.group(1)));
        }

        matcher.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static Object[] filterForStringFormat(Object[] args) {
        // when invoked with a null argument we get a null args instead of an
        // array with a null value.

        if (args == null)
            return NO_ARGS;

        Object[] result = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) {
                continue;
            }
            result[i] = args[i];
        }
        return result;
    }

    /**
     * return all messages for a locale
     * 
     * @param locale
     *            the locale code eg. fr, fr_FR
     * @return messages as a {@link java.util.Properties java.util.Properties}
     */
    public static Properties all(String locale) {
        if (locale == null || "".equals(locale)) {
            return defaults;
        }
        Properties mergedMessages = new Properties();
        mergedMessages.putAll(defaults);
        if (locales != null) {
            if (locale.length() == 5 && locales.containsKey(locale.substring(0, 2))) {
                mergedMessages.putAll(locales.get(locale.substring(0, 2)));
            }
            mergedMessages.putAll(locales.get(locale));
        }
        return mergedMessages;
    }

}
