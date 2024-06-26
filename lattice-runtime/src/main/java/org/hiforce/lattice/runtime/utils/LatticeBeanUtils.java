package org.hiforce.lattice.runtime.utils;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.lang.reflect.Constructor;

import static org.springframework.core.annotation.AnnotationUtils.getAnnotation;

/**
 * @author Rocky Yu
 * @since 2022/9/16
 */
@Slf4j
public class LatticeBeanUtils {


    public static void autowireBean(Object object) {
        ApplicationContext context = findApplicationContext(object.getClass().getClassLoader());
        if (context != null) {
            try {
                context.getAutowireCapableBeanFactory().autowireBean(object);
            } catch (Throwable e) {
                log.warn("[Lattice]Failed to autowireBean " + object.getClass().getName(), e);
            }
        }
    }

    public static ApplicationContext findApplicationContext(ClassLoader classLoader) {
        return SpringApplicationContextHolder.getContext();
    }


    public static <T> T getAndCreateSpringBeanViaClass(Class<?> beanClass, Object... values) {
        if (null == beanClass) {
            return null;
        }
        //尝试从Spring注册的Bean中获取
        try {
            // 当前类是否加了spring注解
            SpringAnnotationResult result = getSpringAnnotationResult(beanClass);
            if (result.isHasAnnotation()) {
                T t = StringUtils.isEmpty(result.getValue())
                        ? SpringApplicationContextHolder.getSpringBean(beanClass)   // 通过class获取
                        : SpringApplicationContextHolder.getSpringBean(result.getValue());  // 通过bean name获取
                if (null != t) {
                    return t;
                }
            }
            // 通过className获取，没有就报错
            T t = SpringApplicationContextHolder
                    .getSpringBean(StringUtils.uncapitalize(beanClass.getSimpleName()));
            if (null != t) {
                return t;
            }
            // 创建spring bean
            return createSpringBeanInstance(beanClass, values);
        } catch (Throwable th) {
            // 报错兜底
            return createSpringBeanInstance(beanClass, values);
        }
    }

    @SuppressWarnings("all")
    private static <T> T createSpringBeanInstance(@Nonnull Class<?> beanClass, Object... values) {
        try {
            ApplicationContext context = SpringApplicationContextHolder.getContext();
            if (context != null) {
                // spring 容器创建 bean
                return (T) context.getAutowireCapableBeanFactory().createBean(beanClass);
            }
            // java 反射创建实例
            return (T) beanClass.newInstance();
        } catch (Throwable e) {
            // 基于参数个数协商构造函数创建实例
            return createBeanInstance(beanClass, values);
        }
    }

    @SuppressWarnings("all")
    private static <T> T createBeanInstance(@Nonnull Class<?> beanClass, Object... values) {
        try {
            try {
                if (null == values || 0 == values.length) {
                    return (T) beanClass.newInstance();
                }
            } catch (Exception ex) {
                //values maybe is null, but bean has constructor with args.
                Constructor constructor = findFirstConstructor(beanClass);
                return (T) createBeanInstanceWithNullValues(constructor);
            }

            Constructor constructor = getMatchedConstructor(beanClass, values);
            if (null == constructor) {
                return null;
            }
            return (T) constructor.newInstance(values);
        } catch (Throwable e) {
            log.warn("[Lattice]Failed to create spring bean instance", e);
            return null;
        }
    }

    private static <T> T createBeanInstanceWithNullValues(Constructor<T> constructor) throws Exception {
        Class<?>[] paramTypes = constructor.getParameterTypes();
        Object[] values = new Object[paramTypes.length];
        return constructor.newInstance(values);
    }

    private static Constructor findFirstConstructor(Class<?> beanClass) {
        if (beanClass.getConstructors().length == 0) {
            return null;
        }
        return beanClass.getConstructors()[0];
    }

    private static Constructor<?> getMatchedConstructor(Class<?> beanClass, Object... values) {
        for (Constructor<?> constructor : beanClass.getConstructors()) {
            if (!checkConstructMatched(constructor, values)) {
                continue;
            }
            return constructor;
        }
        return null;
    }

    private static boolean checkConstructMatched(Constructor<?> constructor, Object... values) {

        Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (parameterTypes.length != values.length) {
            return false;
        }
        for (int i = 0; i < values.length; i++) {
            if (null != values[i]) {
                if (!ClassUtils.isAssignable(values[i].getClass(), formatParamType(parameterTypes[i]))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Class<?> formatParamType(Class<?> paramClass) {
        if (!paramClass.isPrimitive()) {
            return paramClass;
        }
        if (int.class.equals(paramClass)) {
            return Integer.class;
        } else if (long.class.equals(paramClass)) {
            return Long.class;
        } else if (double.class.equals(paramClass)) {
            return Double.class;
        } else if (boolean.class.equals(paramClass)) {
            return Boolean.class;
        }
        return paramClass;
    }

    private static SpringAnnotationResult getSpringAnnotationResult(Class<?> beanClass) {
        Service service = getAnnotation(beanClass, Service.class);
        if (null != service) {
            return new SpringAnnotationResult(true, service.value());
        }
        Repository repository = getAnnotation(beanClass, Repository.class);
        if (null != repository) {
            return new SpringAnnotationResult(true, repository.value());
        }
        Component component = getAnnotation(beanClass, Component.class);
        if (null != component) {
            return new SpringAnnotationResult(true, component.value());
        }
        return new SpringAnnotationResult(false, null);
    }

}

class SpringAnnotationResult {

    @Getter
    private final boolean hasAnnotation;

    @Getter
    private String value;

    public SpringAnnotationResult(boolean hasAnnotation, String value) {
        this.hasAnnotation = hasAnnotation;
        this.value = value;
    }
}

