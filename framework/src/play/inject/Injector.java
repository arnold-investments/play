package play.inject;

import play.Play;

public class Injector {

    private static BeanSource beanSource = new DefaultBeanSource();

    public static void setBeanSource(BeanSource beanSource) {
        Injector.beanSource = beanSource;
    }

    public static <T> T getBeanOfType(String className) {
        return getBeanOfType((Class<T>) Play.classloader.loadApplicationClass(className));
    }

    public static <T> T getBeanOfType(Class<T> clazz) {
        return beanSource.getBeanOfType(clazz);
    }

}
