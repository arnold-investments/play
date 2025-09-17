package play.data.binding;

import play.Logger;
import play.mvc.Context;

import java.beans.ConstructorProperties;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;

final class ConstructorBindingSupport {

	static boolean shouldUseConstructorBinding(Class<?> clazz) {
		// If there is no no-arg ctor, or there are final fields, or type/any ctor is annotated
		boolean hasNoArg = hasNoArgCtor(clazz);
		boolean annotated = clazz.isAnnotationPresent(BindConstructor.class) || anyCtorAnnotated(clazz, BindConstructor.class);
		return !hasNoArg || annotated;
	}

	static Object bindViaConstructor(
        Context context,
        ParamNode paramNode,
        Class<?> clazz,
        BindingAnnotations bindingAnnotations
    ) {
		Constructor<?> ctor = selectConstructor(clazz);
		String[] names = parameterNames(clazz, ctor);
		Parameter[] params = ctor.getParameters();
		Class<?>[] types = ctor.getParameterTypes();
		Type[] gtypes = ctor.getGenericParameterTypes();

		if (names.length != params.length) {
			throw new RuntimeException("Cannot resolve parameter names for " + clazz.getName()
					+ ". Use @ConstructorProperties or compile with -parameters.");
		}

		Object[] args = new Object[params.length];
		for (int i = 0; i < params.length; i++) {
			String name = names[i];
			Class<?> pt = types[i];
			Type gt = gtypes[i];

			// Merge constructor param annotations with incoming binding annotations
			Annotation[] paramAnns = merge(bindingAnnotations.annotations, params[i].getAnnotations());
			BindingAnnotations eff = new BindingAnnotations(paramAnns, bindingAnnotations.getProfiles());

			ParamNode child = paramNode == null ? null : paramNode.getChild(name);
			Object v = Binder.internalBind(context, child, pt, gt, eff);

			if (v == Binder.MISSING || v == Binder.NO_BINDING) {
				v = null;
			}
			if (v == null && pt.isPrimitive()) {
				v = primitiveDefault(pt);
			}
			args[i] = v;
		}

		try {
			if (!ctor.canAccess(null)) ctor.setAccessible(true);
			return ctor.newInstance(args);
		} catch (ReflectiveOperationException e) {
			Logger.error(e, "Failed to construct %s via %s", clazz.getName(), ctor);
			throw new RuntimeException("Constructor binding failed for " + clazz.getName(), e);
		}
	}

	// ---- selection & metadata ----

	private static Constructor<?> selectConstructor(Class<?> clazz) {
		// 1) Prefer explicitly annotated ctor
		for (Constructor<?> c : clazz.getDeclaredConstructors()) {
			if (c.isAnnotationPresent(BindConstructor.class)) return c;
		}
		// 2) If exactly one ctor, use it
		Constructor<?>[] all = clazz.getDeclaredConstructors();
		if (all.length == 1) return all[0];

		// 3) Prefer ctor with @ConstructorProperties (names supplied)
		for (Constructor<?> c : all) {
			if (c.isAnnotationPresent(ConstructorProperties.class)) return c;
		}

		// 4) Fall back to a public ctor if available, otherwise the first declared
		for (Constructor<?> c : all) {
			if (Modifier.isPublic(c.getModifiers())) return c;
		}
		return all[0];
	}

	private static String[] parameterNames(Class<?> clazz, Constructor<?> ctor) {
		// @ConstructorProperties has authoritative names
		ConstructorProperties cp = ctor.getAnnotation(ConstructorProperties.class);
		if (cp != null) return cp.value();

		// else rely on -parameters (Parameter.isNamePresent)
		Parameter[] ps = ctor.getParameters();
		boolean ok = true;
		String[] names = new String[ps.length];
		for (int i = 0; i < ps.length; i++) {
			if (ps[i].isNamePresent()) {
				names[i] = ps[i].getName();
			} else {
				ok = false; break;
			}
		}
		if (ok) return names;

		// else try to infer from bean properties (match by type & order)
		// Last resort: use property names from BeanWrapper order.
		BeanWrapper bw = Binder.getBeanWrapper(clazz);
		List<BeanWrapper.Property> props = new ArrayList<>(bw.getWrappers());
		if (props.size() == ps.length) {
			String[] byPropOrder = new String[ps.length];
			for (int i = 0; i < ps.length; i++) {
				byPropOrder[i] = props.get(i).getName();
			}
			return byPropOrder;
		}

		return new String[0]; // will trigger error in caller
	}

	private static boolean hasNoArgCtor(Class<?> clazz) {
		try {
			clazz.getDeclaredConstructor();
			return true;
		} catch (NoSuchMethodException e) {
			return false;
		}
	}

	private static boolean anyCtorAnnotated(Class<?> clazz, Class<? extends Annotation> ann) {
		for (Constructor<?> c : clazz.getDeclaredConstructors()) {
			if (c.isAnnotationPresent(ann)) return true;
		}
		return false;
	}

	private static Annotation[] merge(Annotation[] a, Annotation[] b) {
		if ((a == null || a.length == 0) && (b == null || b.length == 0)) return new Annotation[0];
		if (a == null || a.length == 0) return b;
		if (b == null || b.length == 0) return a;
		Annotation[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	private static Object primitiveDefault(Class<?> t) {
		if (t == boolean.class) return false;
		if (t == byte.class)    return (byte) 0;
		if (t == short.class)   return (short) 0;
		if (t == int.class)     return 0;
		if (t == long.class)    return 0L;
		if (t == float.class)   return 0f;
		if (t == double.class)  return 0d;
		if (t == char.class)    return '\0';
		throw new IllegalArgumentException("Not a primitive: " + t);
	}

	private ConstructorBindingSupport() {}
}
