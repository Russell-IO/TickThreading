package me.nallar.tickthreading.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import me.nallar.tickthreading.Log;
import me.nallar.unsafe.UnsafeUtil;

/**
 * Only for use when performance doesn't matter
 */
public enum ReflectUtil {
	;

	public static Field getField(Class c, String name) {
		Field field = null;
		do {
			try {
				field = c.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
			}
		} while (field == null && (c = c.getSuperclass()) != Object.class);
		field.setAccessible(true);
		return field;
	}

	public static Method getMethod(Class c, String name) {
		Method method = null;
		do {
			for (Method method_ : c.getDeclaredMethods()) {
				if (method_.getName().equals(name)) {
					if (method != null) {
						Log.severe("Two possible matches: " + method + ", " + method_);
					}
					method = method_;
				}
			}
		} while (method == null && (c = c.getSuperclass()) != Object.class);
		method.setAccessible(true);
		return method;
	}

	public static <T> T get(Object o, String fieldName) {
		try {
			return (T) getField(o.getClass(), fieldName).get(o);
		} catch (IllegalAccessException e) {
			throw UnsafeUtil.throwIgnoreChecked(e);
		}
	}

	public static <T> T call(Object o, String methodName, Object... args) {
		try {
			return (T) getMethod(o.getClass(), methodName).invoke(o, args);
		} catch (Throwable t) {
			throw UnsafeUtil.throwIgnoreChecked(t);
		}
	}

	public static int getInt(Object o, String fieldName) {
		try {
			return getField(o.getClass(), fieldName).getInt(o);
		} catch (IllegalAccessException e) {
			throw UnsafeUtil.throwIgnoreChecked(e);
		}
	}
}