package play.data.validation;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import net.sf.oval.Validator;
import net.sf.oval.configuration.annotation.AbstractAnnotationCheck;
import net.sf.oval.context.OValContext;
import play.mvc.Context;
import play.utils.Utils.AlternativeDateFormat;
import play.exceptions.UnexpectedException;
import play.libs.I18N;

@SuppressWarnings("serial")
public class InFutureCheck extends AbstractAnnotationCheck<InFuture> implements NeedContext {

    static final String mes = "validation.future";

    Date reference;

    private Context context;

    public InFutureCheck(Context context) {
        this.context = context;
    }
    
    public InFutureCheck() {
    }

    @Override
    public void setContext(Context context) {
        this.context = context;
    }

    @Override
    public void configure(InFuture future) {
        try {
            this.reference = future.value().equals("") ? new Date() : AlternativeDateFormat.getDefaultFormatter().parse(future.value());
        } catch (ParseException ex) {
            throw new UnexpectedException("Cannot parse date " +future.value(), ex);
        }
        if(!future.value().equals("") && future.message().equals(mes)) {
            setMessage("validation.after");
        } else {
            setMessage(future.message());
        }
    }

    @Override
    public boolean isSatisfied(Object validatedObject, Object value, OValContext context, Validator validator) {
        requireMessageVariablesRecreation();
        if (value == null) {
            return true;
        }
        if (value instanceof Date) {
            try {
                return reference.before((Date)value);
            } catch (Exception e) {
                return false;
            }
        }
        if (value instanceof Long) {
            try {
                return reference.before(new Date((Long) value));
            } catch (Exception e) {
                return false;
            }
        }
        if (value instanceof LocalDate) {
            try {
                return reference.before(Date.from(((LocalDate) value).atStartOfDay(ZoneId.systemDefault()).toInstant()));
            } catch (Exception e) {
                return false;
            }
        }
        if (value instanceof LocalDateTime) {
            try {
                return reference.before(Date.from(((LocalDateTime) value).atZone(ZoneId.systemDefault()).toInstant()));
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    @Override
    public Map<String, String> createMessageVariables() {
        Map<String, String> messageVariables = new HashMap<>();
        messageVariables.put("reference", new SimpleDateFormat(I18N.getDateFormat(context)).format(reference));
        return messageVariables;
    }
   
}
