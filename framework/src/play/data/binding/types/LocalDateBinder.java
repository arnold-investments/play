/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>.
 */
package play.data.binding.types;

import play.data.binding.TypeBinder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.time.LocalDate;
import play.mvc.Context;

public class LocalDateBinder implements TypeBinder<LocalDate> {

    @Override
    public LocalDate bind(Context context, String name, Annotation[] annotations, String value, Class<?> actualClass, Type genericType) {
        return value != null && !value.isBlank() ? LocalDate.parse(value) : null;
    }
}
