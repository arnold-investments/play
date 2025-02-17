package play.data.validation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.sf.oval.configuration.annotation.AbstractAnnotationCheck;
import play.Play;
import play.exceptions.UnexpectedException;
import play.mvc.Context;

public class Validation {
    public final Map<Object, String> keys = new HashMap<>();
    final List<Error> errors = new ArrayList<>();
    boolean keep = false;

    public Validation() {
    }

    /**
     * @return The list of all errors
     */
    @SuppressWarnings({"serial", "unused"})
    public List<Error> errors() {

        return new ArrayList<Error>(errors) {

            public Error forKey(String key) {
                return error(key);
            }

            public List<Error> allForKey(String key) {
                return errors(key);
            }
        };
    }

    /**
     * @return All errors keyed by field name
     */
    public Map<String, List<Error>> errorsMap() {
        Map<String, List<Error>> result = new LinkedHashMap<>();
        for (Error error : errors()) {
            result.put(error.key, errors(error.key));
        }
        return result;
    }

    /**
     * Add an error
     * @param field Field name
     * @param message Message key
     * @param variables Message variables
     */
    public void addError(Context context, String field, String message, String... variables) {
        insertError(context, errors.size(), field, message,  variables);
    }
    
    /**
     * Insert an error at the specified position in this list.
     * @param index index at which the specified element is to be inserted
     * @param field Field name
     * @param message Message key
     * @param variables Message variables
     */
    public void insertError(Context context, int index, String field, String message, String... variables) {
        Error error = error(field);
        if (error == null || !error.message.equals(message)) {
            errors.add(index, new Error(context, field, message, variables));
        }
    }
    
    /**
     * Remove all errors on a field with the given message
     * @param field Field name
     * @param message Message key
     */
     public void removeErrors(String field, String message) {
         errors.removeIf(error -> error.key != null && error.key.equals(field) && error.message.equals(message));
     }
     
    /**
    * Remove all errors on a field
    * @param field Field name
    */
    public void removeErrors(String field) {
        errors.removeIf(error -> error.key != null && error.key.equals(field));
    }
    
    

    /**
     * @return True if the current request has errors
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * @param field The field name
     * @return true if field has some errors
     */
    public boolean hasErrors(String field){
        return error(field) != null;
    }
    
    /**
     * @param field The field name
     * @return First error related to this field
     */
    public Error error(String field) {
        for (Error error : errors) {
            if (error.key!=null && error.key.equals(field)) {
                return error;
            }
        }
        return null;
    }

    /**
     * @param field The field name
     * @return All errors related to this field
     */
    public List<Error> errors(String field) {
        List<Error> errors = new ArrayList<>();
        for (Error error : errors) {
            if (error.key!=null && error.key.equals(field)) {
                errors.add(error);
            }
        }
        return errors;
    }

    /**
     * Keep errors for the next request (will be stored in a cookie)
     */
    public void keep() {
        keep = true;
    }

    /**
     * @param field The field name
     * @return True is there are errors related to this field
     */
    public boolean hasError(String field) {
        return error(field) != null;
    }

    public void clear() {
        errors.clear();
    }

    // ~~~~ Integration helper
    public static Map<String, List<Validator>> getValidators(Class<?> clazz, String name) {
        Map<String, List<Validator>> result = new HashMap<>();
        searchValidator(clazz, name, result);
        return result;
    }

    public static List<Validator> getValidators(Class<?> clazz, String property, String name) {
        try {
            List<Validator> validators = new ArrayList<>();
            while (!clazz.equals(Object.class)) {
                try {
                    Field field = clazz.getDeclaredField(property);
                    for (Annotation annotation : field.getDeclaredAnnotations()) {
                        if (annotation.annotationType().getName().startsWith("play.data.validation")) {
                            Validator validator = new Validator(annotation);
                            validators.add(validator);
                            if (annotation.annotationType().equals(InFuture.class)) {
                                validator.params.put("reference", ((InFuture) annotation).value());
                            }
                            if (annotation.annotationType().equals(InPast.class)) {
                                validator.params.put("reference", ((InPast) annotation).value());
                            }
                        }
                    }
                    break;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            return validators;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    static void searchValidator(Class<?> clazz, String name, Map<String, List<Validator>> result) {
        for (Field field : clazz.getDeclaredFields()) {

            List<Validator> validators = new ArrayList<>();
            String key = name + "." + field.getName();
            boolean containsAtValid = false;
            for (Annotation annotation : field.getDeclaredAnnotations()) {
                if (annotation.annotationType().getName().startsWith("play.data.validation")) {
                    Validator validator = new Validator(annotation);
                    validators.add(validator);
                    if (annotation.annotationType().equals(InFuture.class)) {
                        validator.params.put("reference", ((InFuture) annotation).value());
                    }
                    if (annotation.annotationType().equals(InPast.class)) {
                        validator.params.put("reference", ((InPast) annotation).value());
                    }

                }
                if (annotation.annotationType().equals(Valid.class)) {
                    containsAtValid = true;
                }
            }
            if (!validators.isEmpty()) {
                result.put(key, validators);
            }
            if (containsAtValid) {
                searchValidator(field.getType(), key, result);
            }
        }
    }

    public static class Validator {

        public final Annotation annotation;
        public final Map<String, Object> params = new HashMap<>();

        public Validator(Annotation annotation) {
            this.annotation = annotation;
        }
    }

    // ~~~~ Validations
    public static class ValidationResult {

        public boolean ok = false;
        public Error error;

        public ValidationResult message(String message) {
            if (error != null) {
                error.message = message;
            }
            return this;
        }

        public ValidationResult key(String key) {
            if (error != null) {
                error.key = key;
            }
            return this;
        }
    }

    public ValidationResult required(Context context, String key, Object o) {
        RequiredCheck check = new RequiredCheck();
        return applyCheck(context, check, key, o);
    }

    public ValidationResult min(Context context, String key, Object o, double min) {
        MinCheck check = new MinCheck();
        check.min = min;
        return applyCheck(context, check, key, o);
    }

    public ValidationResult max(Context context, String key, Object o, double max) {
        MaxCheck check = new MaxCheck();
        check.max = max;
        return applyCheck(context, check, key, o);
    }

    public ValidationResult future(Context context, String key, Object o, Date reference) {
        InFutureCheck check = new InFutureCheck(context);
        check.reference = reference;
        return applyCheck(context, check, key, o);
    }

    public ValidationResult future(Context context, String key, Object o) {
        InFutureCheck check = new InFutureCheck(context);
        check.reference = new Date();
        return applyCheck(context, check, key, o);
    }

    public ValidationResult past(Context context, String key, Object o, Date reference) {
        InPastCheck check = new InPastCheck(context);
        check.reference = reference;
        return applyCheck(context, check, key, o);
    }

    public ValidationResult past(Context context, String key, Object o) {
        InPastCheck check = new InPastCheck(context);
        check.reference = new Date();
        return applyCheck(context, check, key, o);
    }

    public ValidationResult match(Context context, String key, Object o, String pattern) {
        MatchCheck check = new MatchCheck();
        check.pattern = Pattern.compile(pattern);
        return applyCheck(context, check, key, o);
    }

    public ValidationResult email(Context context, String key, Object o) {
        EmailCheck check = new EmailCheck();
        return applyCheck(context, check, key, o);
    }
    public ValidationResult url(Context context, String key, Object o) {
        URLCheck check = new URLCheck();
        return applyCheck(context, check, key, o);
    }

    public ValidationResult phone(Context context, String key, Object o) {
        PhoneCheck check = new PhoneCheck();
        return applyCheck(context, check, key, o);
    }

    public ValidationResult ipv4Address(Context context, String key, Object o) {
        IPv4AddressCheck check = new IPv4AddressCheck();
        return applyCheck(context, check, key, o);
    }

    public ValidationResult ipv6Address(Context context, String key, Object o) {
        IPv6AddressCheck check = new IPv6AddressCheck();
        return applyCheck(context, check, key, o);
    }

    public ValidationResult isTrue(Context context, String key, Object o) {
        IsTrueCheck check = new IsTrueCheck();
        return applyCheck(context, check, key, o);
    }

    public ValidationResult range(Context context, String key, Object o, double min, double max) {
        RangeCheck check = new RangeCheck();
        check.min = min;
        check.max = max;
        return applyCheck(context, check, key, o);
    }

    public ValidationResult minSize(Context context, String key, Object o, int minSize) {
        MinSizeCheck check = new MinSizeCheck();
        check.minSize = minSize;
        return applyCheck(context, check, key, o);
    }

    public ValidationResult maxSize(Context context, String key, Object o, int maxSize) {
        MaxSizeCheck check = new MaxSizeCheck();
        check.maxSize = maxSize;
        return applyCheck(context, check, key, o);
    }

    public ValidationResult valid(Context context, String key, Object o) {
        ValidCheck check = new ValidCheck(context);
        check.key = key;
        return applyCheck(context, check, key, o);
    }

    ValidationResult applyCheck(Context context, AbstractAnnotationCheck<?> check, String key, Object o) {
        try {
            ValidationResult result = new ValidationResult();
            if (!check.isSatisfied(o, o, null, null)) {
                Error error = new Error(
                    context,
                    key,
                    check.getClass().getDeclaredField("mes").get(null) + "",
                    check.getMessageVariables() == null
                        ? new String[0]
                        : check.getMessageVariables().values().toArray(new String[0]),
                    check.getSeverity()
                );
                errors.add(error);
                result.error = error;
                result.ok = false;
            } else {
                result.ok = true;
            }
            return result;
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public static Object willBeValidated(Object value) {
        return Play.pluginCollection.willBeValidated(value);
    }
}
