package play.data.binding.types;

import play.data.binding.TypeBinder;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import play.data.binding.AnnotationHelper;
import play.i18n.Lang;
import play.libs.I18N;
import play.mvc.Context;

/**
 * Binder that support Calendar class.
 */
public class CalendarBinder implements TypeBinder<Calendar> {

    @Override
    public Calendar bind(Context context, String name, Annotation[] annotations, String value, Class<?> actualClass, Type genericType) throws Exception {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        Calendar cal = Calendar.getInstance(Lang.getLocale(context));

        Date date = AnnotationHelper.getDateAs(context, annotations, value);
        if (date != null) {
            cal.setTime(date);
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat(I18N.getDateFormat(context));
            sdf.setLenient(false);
            cal.setTime(sdf.parse(value));
        }
        return cal;
    }
}
