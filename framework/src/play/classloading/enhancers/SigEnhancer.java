package play.classloading.enhancers;

import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.LocalVariableAttribute;
import javassist.bytecode.Opcode;
import javassist.bytecode.annotation.Annotation;
import play.classloading.ApplicationClasses.ApplicationClass;

/**
 * Compute a unique hash for the class signature and constant properties.
 */
public class SigEnhancer extends Enhancer {

    @Override
    public void computeSignatures(ApplicationClass applicationClass) throws Exception {
        if (isScala(applicationClass)) {
            return;
        }

        CtClass ctClass = makeClass(applicationClass);
        if (isScalaObject(ctClass)) {
            return;
        }

        StringBuilder sigChecksum = new StringBuilder();
        StringBuilder staticFinalSigChecksum = new StringBuilder();

        sigChecksum.append("Class->").append(ctClass.getName()).append(":");
        if (ctClass.getGenericSignature() != null) {
            sigChecksum.append(ctClass.getGenericSignature());
        }
        try {
            CtClass superClass = ctClass.getSuperclass();
            if (superClass != null) {
                sigChecksum.append(" extends ").append(superClass.getName());
            }
        } catch (javassist.NotFoundException e) {
            // Ignore
        }
        try {
            for (CtClass i : ctClass.getInterfaces()) {
                sigChecksum.append(" implements ").append(i.getName());
            }
        } catch (javassist.NotFoundException e) {
            // Ignore
        }
        for (Annotation annotation : getAnnotations(ctClass).getAnnotations()) {
            sigChecksum.append(annotation).append(",");
        }

        for (CtField field : ctClass.getDeclaredFields()) {
            sigChecksum.append(" Field->").append(ctClass.getName()).append(" ").append(field.getSignature()).append(":");
            sigChecksum.append(field.getSignature());
            if (field.getGenericSignature() != null) {
                sigChecksum.append(field.getGenericSignature());
            }
            if (javassist.Modifier.isStatic(field.getModifiers()) && javassist.Modifier.isFinal(field.getModifiers())) {
                // Capture constant values for inlinable fields
                Object constantValue = field.getConstantValue();
                if (constantValue != null) {
                    staticFinalSigChecksum.append(field.getName()).append(":").append(field.getSignature()).append("=").append(constantValue).append(",");
                    sigChecksum.append("=").append(constantValue);
                }
            }
            for (Annotation annotation : getAnnotations(field).getAnnotations()) {
                sigChecksum.append(annotation).append(",");
            }
        }

        for (CtMethod method : ctClass.getDeclaredMethods()) {
            sigChecksum.append(" Method->").append(method.getName()).append(method.getSignature()).append(":");
            if (method.getGenericSignature() != null) {
                sigChecksum.append(method.getGenericSignature());
            }
            for (Annotation annotation : getAnnotations(method).getAnnotations()) {
                sigChecksum.append(annotation).append(" ");
            }
            // Signatures names
            CodeAttribute codeAttribute = (CodeAttribute) method.getMethodInfo().getAttribute("Code");
            if (codeAttribute == null || javassist.Modifier.isAbstract(method.getModifiers())) {
                continue;
            }
            LocalVariableAttribute localVariableAttribute = (LocalVariableAttribute) codeAttribute.getAttribute("LocalVariableTable");
            if (localVariableAttribute != null) {
                for (int i = 0; i < localVariableAttribute.tableLength(); i++) {
                    sigChecksum.append(localVariableAttribute.variableName(i)).append(",");
                }
            }
        }

        if (ctClass.getClassInitializer() != null) {
            sigChecksum.append("Static Code->");
            for (CodeIterator i = ctClass.getClassInitializer().getMethodInfo().getCodeAttribute().iterator(); i.hasNext();) {
                int index = i.next();
                int op = i.byteAt(index);
                sigChecksum.append(op);
                if (op == Opcode.LDC) {
                    sigChecksum.append("[").append(i.get().getConstPool().getLdcValue(i.byteAt(index + 1))).append("]");
                }
                sigChecksum.append(".");
            }
        }

        if (ctClass.getName().endsWith("$")) {
            sigChecksum.append("Singletons->");
            for (CodeIterator i = ctClass.getDeclaredConstructors()[0].getMethodInfo().getCodeAttribute().iterator(); i.hasNext();) {
                int index = i.next();
                int op = i.byteAt(index);
                sigChecksum.append(op);
                if (op == Opcode.LDC) {
                    sigChecksum.append("[").append(i.get().getConstPool().getLdcValue(i.byteAt(index + 1))).append("]");
                }
                sigChecksum.append(".");
            }
        }

        // Done.
        applicationClass.sigChecksum = sigChecksum.toString().hashCode();
        applicationClass.staticFinalSigChecksum = staticFinalSigChecksum.length() > 0 ? staticFinalSigChecksum.toString().hashCode() : 0;
        applicationClass.staticFinalSigComputed = true;
    }
}
