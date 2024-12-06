package play.classloading.enhancers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Stack;
import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.annotation.Annotation;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import javassist.expr.Handler;
import play.Logger;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.exceptions.UnexpectedException;

/**
 * JB: NOT USED AS ENHANCER ANYMORE!
 */
public class ControllersEnhancer {

    public static final ThreadLocal<Stack<String>> currentAction = new ThreadLocal<>();

    /**
     * Mark class that need controller enhancement
     */
    public interface ControllerSupport {
    }

    /**
     * Runtime part needed by the instrumentation
     */
    public static class ControllerInstrumentation {

        public static boolean isActionCallAllowed() {
            return allow.get();
        }

        public static void initActionCall() {
            allow.set(true);
        }

        public static void stopActionCall() {
            allow.set(false);
        }
        static final ThreadLocal<Boolean> allow = new ThreadLocal<>();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface ByPass {
    }

    static String generateValidReturnStatement(CtClass type) {
        if (type.equals(CtClass.voidType)) {
            return "return;";
        }
        if (type.equals(CtClass.booleanType)) {
            return "return false;";
        }
        if (type.equals(CtClass.charType)) {
            return "return '';";
        }
        if (type.equals(CtClass.byteType)) {
            return "return (byte)0;";
        }
        if (type.equals(CtClass.doubleType)) {
            return "return (double)0;";
        }
        if (type.equals(CtClass.floatType)) {
            return "return (float)0;";
        }
        if (type.equals(CtClass.intType)) {
            return "return (int)0;";
        }
        if (type.equals(CtClass.longType)) {
            return "return (long)0;";
        }
        if (type.equals(CtClass.shortType)) {
            return "return (short)0;";
        }
        return "return null;";
    }
}
