package play.i18n;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import play.Logger;
import play.Play;
import play.mvc.Context;
import play.mvc.Http;
import play.mvc.Http.Response;
import play.mvc.Scope;

/**
 * Language support
 */
public class Lang {

    /**
     * Retrieve the current language or null
     *
     * @return The current language (fr, ja, it ...) or null
     */
    public static String get(Context context) {
        String locale = context.getLocaleStr();
        if (locale == null) {
            // don't have current locale for this request - must try to resolve it
            Http.Request currentRequest = context.getRequest();
            if (currentRequest != null) {
                // we have a current request - lets try to resolve language from it
                resolveFrom(context);
            } else {
                // don't have current request - just use default
                setDefaultLocale(context);
            }
            // get the picked locale
            locale = context.getLocaleStr();
        }
        return locale;
    }


    /**
     * Force the current language
     *
     * @param locale (fr, ja, it ...)
     * @return false if the language is not supported by the application
     */
    public static boolean set(Context context, String locale) {
        if (locale.isEmpty() || Play.langs.contains(locale)) {
            context.setLocaleStr(locale);
            context.setLocale(Locale.forLanguageTag(locale));
            return true;
        } else {
            Logger.warn("Locale %s is not defined in your application.conf", locale);
            return false;
        }
    }

    /**
     * Clears the current language - This wil trigger resolving language from request
     * if not manually set.
     */
    public static void clear(Context context) {
        context.setLocaleStr(null);
        context.setLocale(null);
    }


    /**
     * Change language for next requests
     *
     * @param locale (e.g. "fr", "ja", "it", "en_ca", "fr_be", ...)
     */
    public static void change(Context context, String locale) {
        String closestLocale = findClosestMatch(Collections.singleton(locale));
        if (closestLocale == null) {
            // Give up
            return;
        }
        if (set(context, closestLocale)) {
            Response response = context.getResponse();
            if (response != null) {
                // We have a current response in scope - set the language-cookie to store the selected language for the next requests
                response.setCookie(Play.configuration.getProperty("application.lang.cookie", "PLAY_LANG"), locale, null, "/", null, Scope.COOKIE_SECURE);
            }
        }

    }

    /**
     * Given a set of desired locales, searches the set of locales supported by this Play! application and returns the closest match.
     *
     * @param desiredLocales a collection of desired locales. If the collection is ordered, earlier locales are preferred over later ones.
     *                       Locales should be of the form "[language]_[country" or "[language]", e.g. "en_CA" or "en".
     *                       The locale strings are case insensitive (e.g. "EN_CA" is considered the same as "en_ca").
     *                       Locales can also be of the form "[language]-[country", e.g. "en-CA" or "en".
     *                       They are still case insensitive, though (e.g. "EN-CA" is considered the same as "en-ca").
     * @return the closest matching locale. If no closest match for a language/country is found, null is returned
     */
    private static String findClosestMatch(Collection<String> desiredLocales) {
        ArrayList<String> cleanLocales = new ArrayList<>(desiredLocales.size());
        //look for an exact match
        for (String a : desiredLocales) {
            a = a.replace('-', '_');
            cleanLocales.add(a);
            for (String locale : Play.langs) {
                if (locale.equalsIgnoreCase(a)) {
                    return locale;
                }
            }
        }
        // Exact match not found, try language-only match.
        for (String a : cleanLocales) {
            int splitPos = a.indexOf('_');
            if (splitPos > 0) {
                a = a.substring(0, splitPos);
            }
            for (String locale : Play.langs) {
                String langOnlyLocale;
                int localeSplitPos = locale.indexOf('_');
                if (localeSplitPos > 0) {
                    langOnlyLocale = locale.substring(0, localeSplitPos);
                } else {
                    langOnlyLocale = locale;
                }
                if (langOnlyLocale.equalsIgnoreCase(a)) {
                    return locale;
                }
            }
        }

        // We did not find a anything
        return null;
    }

    /**
     * Guess the language for current request in the following order:
     * <ol>
     * <li>if a <b>PLAY_LANG</b> cookie is set, use this value</li>
     * <li>if <b>Accept-Language</b> header is set, use it only if the Play! application allows it.<br/>supported language may be defined in application configuration, eg : <em>play.langs=fr,en,de)</em></li>
     * <li>otherwise, server's locale language is assumed
     * </ol>
     */
    private static void resolveFrom(Context context) {
        // Check a cookie
        String cn = Play.configuration.getProperty("application.lang.cookie", "PLAY_LANG");
        if (context.getRequest().cookies.containsKey(cn)) {
            String localeFromCookie = context.getRequest().cookies.get(cn).value;
            if (localeFromCookie != null && localeFromCookie.trim().length() > 0) {
                if (set(context, localeFromCookie)) {
                    // we're using locale from cookie
                    return;
                }
                // could not use locale from cookie - clear the locale-cookie
                context.getResponse().setCookie(cn, "", null, "/", null, Scope.COOKIE_SECURE);

            }

        }
        String closestLocaleMatch = findClosestMatch(context.getRequest().acceptLanguage());
        if (closestLocaleMatch != null) {
            set(context, closestLocaleMatch);
        } else {
            // Did not find anything - use default
            setDefaultLocale(context);
        }

    }

    public static void setDefaultLocale(Context context) {
        if (Play.langs.isEmpty()) {
            set(context, "");
        } else {
            set(context, Play.langs.getFirst());
        }
    }

    /**
     * @return the default locale if the Locale cannot be found otherwise the locale
     * associated to the current Lang.
     */
    public static Locale getLocale(Context context) {
        return getLocaleOrDefault(get(context));
    }

    public static Locale getLocaleOrDefault(String localeStr) {
        Locale locale = getLocale(localeStr);
        if (locale != null) {
            return locale;
        }
        return Locale.getDefault();
    }

    public static Locale getLocale(String localeStr) {
        if (localeStr == null) {
            return null;
        }

        return Locale.of(localeStr);
    }
}
