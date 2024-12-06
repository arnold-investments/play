package play.data.binding.types;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import org.joda.time.DateTime;

import play.data.binding.TypeBinder;
import play.mvc.Context;

/**
 * Binder that support Date class.
 */
public class DateTimeBinder implements TypeBinder<DateTime> {

    private static final DateBinder dateBinder = new DateBinder();

    @Override
    public DateTime bind(Context context, String name, Annotation[] annotations, String value, Class<?> actualClass, Type genericType) throws Exception {
        if (value == null || value.isBlank()) {
            return null;
        }
        return new DateTime(dateBinder.bind(context, name, annotations, value, actualClass, genericType));
    }
}
