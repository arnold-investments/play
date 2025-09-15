package play.data.binding;

import play.Logger;
import play.mvc.Context;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;

/** Minimal Java 16+ record binder for Play 1.x (uses existing Binder internals). */
final class RecordSupport {

	static boolean isRecord(Class<?> t) {
		return t.isRecord();
	}

	static Object bindRecord(
        Context context,
        ParamNode baseNode,
        Class<?> recordType,
        BindingAnnotations parentAnns
    ) {
		RecordComponent[] components = recordType.getRecordComponents();

		// Zero-component records are rare; still call the canonical no-arg ctor
		if (components.length == 0) {
			try {
				Constructor<?> ctor = recordType.getDeclaredConstructor();
				if (!ctor.canAccess(null)) ctor.setAccessible(true);
				return ctor.newInstance();
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException("Failed to construct " + recordType.getName(), e);
			}
		}

		Class<?>[] argTypes = new Class<?>[components.length];
		Object[] args       = new Object[components.length];

		for (int i = 0; i < components.length; i++) {
			RecordComponent c = components[i];
			String compName   = c.getName();
			Class<?> compType = c.getType();

			// Merge annotations: parent + component-level
			Annotation[] merged = merge(parentAnns.annotations, c.getAnnotations());
			BindingAnnotations effective = new BindingAnnotations(merged, parentAnns.getProfiles());

			// Delegate to existing Binder logic for this component
			ParamNode compNode = baseNode.getChild(compName);
			Object v = Binder.internalBind(context, compNode, compType, compType, effective);

			// Normalize “missing/no binding” to null
			if (v == Binder.MISSING || v == Binder.NO_BINDING) {
				v = null;
			}
			// Primitive defaults to avoid NPE on ctor call
			if (v == null && compType.isPrimitive()) {
				v = primitiveDefault(compType);
			}

			argTypes[i] = compType;
			args[i]     = v;
		}

		try {
			Constructor<?> ctor = recordType.getDeclaredConstructor(argTypes);
			if (!ctor.canAccess(null)) ctor.setAccessible(true);
			return ctor.newInstance(args);
		} catch (NoSuchMethodException | InstantiationException |
				 IllegalAccessException | InvocationTargetException e) {
			Logger.error(e, "Failed to call canonical constructor for %s", recordType.getName());
			throw new RuntimeException("Failed to construct record " + recordType.getName(), e);
		}
	}

	// ---- helpers ----

	private static Annotation[] merge(Annotation[] a, Annotation[] b) {
		if ((a == null || a.length == 0) && (b == null || b.length == 0)) return new Annotation[0];
		if (a == null || a.length == 0) return b;
		if (b == null || b.length == 0) return a;
		Annotation[] out = new Annotation[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
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

	private RecordSupport() {}
}
