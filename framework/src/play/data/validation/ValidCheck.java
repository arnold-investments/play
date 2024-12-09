package play.data.validation;

import java.util.Collection;
import java.util.List;
import net.sf.oval.ConstraintViolation;
import net.sf.oval.Validator;
import net.sf.oval.configuration.annotation.AbstractAnnotationCheck;
import net.sf.oval.context.FieldContext;
import net.sf.oval.context.MethodParameterContext;
import net.sf.oval.context.OValContext;
import play.exceptions.UnexpectedException;
import play.mvc.Context;
import play.utils.Java;

@SuppressWarnings("serial")
public class ValidCheck extends AbstractAnnotationCheck<Required> {
    private final Context context;

    public ValidCheck(Context context) {
        this.context = context;
    }

    static final String mes = "validation.object";
    String key;

    @Override
    public boolean isSatisfied(Object validatedObject, Object value, OValContext oValContext, Validator validator) {
        if (value == null) {
            return true;
        }



        try {
            if (oValContext != null) {
                if (oValContext instanceof MethodParameterContext) {
                    MethodParameterContext ctx = (MethodParameterContext) oValContext;
                    String[] paramNames = Java.parameterNames(ctx.getMethod());
                    key = paramNames[ctx.getParameterIndex()];
                }
                if (oValContext instanceof FieldContext) {
                    FieldContext ctx = (FieldContext) oValContext;
                    key = ctx.getField().getName();
                }
            }
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }

        String superKey = context.getValidation().keys.get(validatedObject);

        if (superKey != null) {
            key = superKey + "." + key;
        }
        if(value instanceof Collection<?> valueCollection) {
	        boolean everythingIsValid = true;
            int index = 0;
            for(Object item : valueCollection) {
                if(!validateObject(key + "[" + (index) + "]", item)) {
                    context.getValidation().errors.add(new Error(context, key + "[" + (index) + "]", mes, new String[0]));
                    everythingIsValid = false;
                }
                index++;
            }
            return everythingIsValid;
        } else {
            return validateObject(key, value);
        }
    }

    boolean validateObject(String key, Object value) {
        context.getValidation().keys.put(value, key);
        List<ConstraintViolation> violations = new Validator().validate(value);
        //
        if (violations.isEmpty()) {
            return true;
        } else {
            for (ConstraintViolation violation : violations) {
                if (violation.getContext() instanceof FieldContext) {
                    FieldContext ctx = (FieldContext) violation.getContext();
                    String fkey = (key == null ? "" : key + ".") + ctx.getField().getName();
                    Error error = new Error(
                            context,
                            fkey,
                            violation.getMessage(),
                            violation.getMessageVariables() == null ? new String[0]
                                    : violation.getMessageVariables().values()
                                            .toArray(new String[0]),
                            violation.getSeverity());
                    context.getValidation().errors.add(error);
                }
            }
            return false;
        }
    }
    
}
