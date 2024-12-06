package play.data.binding.types;

import play.data.binding.TypeBinder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import play.mvc.Context;

public class LocalDateTimeBinder implements TypeBinder<LocalDateTime> {

    @Override
    public LocalDateTime bind(Context context, String name, Annotation[] annotations, String value, Class<?> actualClass, Type genericType) {
        return value != null && !value.isBlank() ? LocalDateTime.parse(value) : null;
    }
}
