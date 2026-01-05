package play.classloading.enhancers;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javassist.ClassPath;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.LoaderClassPath;
import javassist.NotFoundException;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.annotation.MemberValue;
import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;

/**
 * Base class for class scanners that compute signatures.
 */
public abstract class Enhancer {

    public static final ClassPool classPool = newClassPool();

    public static ClassPool newClassPool() {
        ClassPool classPool = new ClassPool();
        classPool.appendSystemPath();
        classPool.appendClassPath(new LoaderClassPath(Enhancer.class.getClassLoader()));
        classPool.appendClassPath(new ApplicationClassesClasspath());
        return classPool;
    }

    /**
     * Construct a javassist CtClass from an application class.
     * 
     * @param applicationClass
     *            The application class to construct
     * @return The javassist CtClass construct from the application class
     * @throws IOException
     *             if problem occurred during construction
     */
    public CtClass makeClass(ApplicationClass applicationClass) throws IOException {
        return classPool.makeClass(new ByteArrayInputStream(applicationClass.javaByteCode));
    }

    /**
     * The magic happen here...
     * 
     * @param applicationClass
     *            The application class to construct
     * @throws Exception
     *             if problem occurred during construction
     */
    public abstract void computeSignatures(ApplicationClass applicationClass) throws Exception;

    /**
     * Dumb classpath implementation for javassist hacking
     */
    public static class ApplicationClassesClasspath implements ClassPath {

        @Override
        public InputStream openClassfile(String className) throws NotFoundException {

            if (Play.usePrecompiled) {
                try {
                    File file = Play.getFile("precompiled/java/" + className.replace('.', '/') + ".class");
                    return new FileInputStream(file);
                } catch (Exception e) {
                    Logger.error("Missing class %s", className);
                }
            }
            ApplicationClass appClass = Play.classes.getApplicationClass(className);

            if (appClass.javaByteCode == null) {
                throw new RuntimeException("Trying to visit uncompiled class while scanning. Uncompiled class: " + className);
            }

            return new ByteArrayInputStream(appClass.javaByteCode);
        }

        @Override
        public URL find(String className) {
            if (Play.classes.getApplicationClass(className) != null) {
                String cname = className.replace('.', '/') + ".class";
                try {
                    // return new File(cname).toURL();
                    return new URI("file:/ApplicationClassesClasspath/" + cname).toURL();
                } catch (URISyntaxException | MalformedURLException ignore) {
                }
            }
            return null;
        }
    }

    /**
     * Retrieve all class annotations.
     * 
     * @param ctClass
     *            The given class
     * @return All class annotations
     */
    protected static AnnotationsAttribute getAnnotations(CtClass ctClass) {
        AnnotationsAttribute annotationsAttribute = (AnnotationsAttribute) ctClass.getClassFile()
                .getAttribute(AnnotationsAttribute.visibleTag);
        if (annotationsAttribute == null) {
            annotationsAttribute = new AnnotationsAttribute(ctClass.getClassFile().getConstPool(), AnnotationsAttribute.visibleTag);
            ctClass.getClassFile().addAttribute(annotationsAttribute);
        }
        return annotationsAttribute;
    }

    /**
     * Retrieve all field annotations.
     * 
     * @param ctField
     *            The given field
     * @return All field annotations.
     */
    protected static AnnotationsAttribute getAnnotations(CtField ctField) {
        AnnotationsAttribute annotationsAttribute = (AnnotationsAttribute) ctField.getFieldInfo()
                .getAttribute(AnnotationsAttribute.visibleTag);
        if (annotationsAttribute == null) {
            annotationsAttribute = new AnnotationsAttribute(ctField.getFieldInfo().getConstPool(), AnnotationsAttribute.visibleTag);
            ctField.getFieldInfo().addAttribute(annotationsAttribute);
        }
        return annotationsAttribute;
    }

    /**
     * Retrieve all method annotations.
     * 
     * @param ctMethod
     *            The given methods
     * @return all method annotations.
     */
    protected static AnnotationsAttribute getAnnotations(CtMethod ctMethod) {
        AnnotationsAttribute annotationsAttribute = (AnnotationsAttribute) ctMethod.getMethodInfo()
                .getAttribute(AnnotationsAttribute.visibleTag);
        if (annotationsAttribute == null) {
            annotationsAttribute = new AnnotationsAttribute(ctMethod.getMethodInfo().getConstPool(), AnnotationsAttribute.visibleTag);
            ctMethod.getMethodInfo().addAttribute(annotationsAttribute);
        }
        return annotationsAttribute;
    }

    boolean isScalaObject(CtClass ctClass) throws Exception {
        for (CtClass i : ctClass.getInterfaces()) {
            if (i.getName().equals("scala.ScalaObject")) {
                return true;
            }
        }
        return false;
    }

    boolean isScala(ApplicationClass app) {
        return app.javaFile.getName().endsWith(".scala");
    }
}
