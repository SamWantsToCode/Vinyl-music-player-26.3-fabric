package com.vinylmusic.gui;

import com.vinylmusic.client.Diagnostics;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Connects mouse presses and releases to a screen through Fabric's ScreenMouseEvents.
 * The listener types are found by name at runtime, so a changed listener signature
 * (separate x, y, button or one MouseButtonEvent) cannot break the build.
 * A polling path stays as a backup. A press seen by both paths is counted once.
 */
public final class InputHooks {
	private InputHooks() {
	}

	public interface Listener {
		/** Returns true when the press was used, so the screen underneath should not also see it. */
		boolean press(int button, double x, double y);

		void release(int button);
	}

	private static final String EVENTS = "net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents";
	private static final Map<Screen, Integer> GEN = new WeakHashMap<>();
	private static final long[] LAST_PRESS = new long[3];

	public static volatile boolean pressHooked;
	public static volatile boolean releaseHooked;
	public static volatile int eventPresses;
	public static volatile int pollPresses;

	private static boolean prevLeft;
	private static boolean prevRight;

	public static synchronized int nextGen(Screen screen) {
		int g = GEN.getOrDefault(screen, 0) + 1;
		GEN.put(screen, g);
		return g;
	}

	public static synchronized boolean isCurrent(Screen screen, int gen) {
		return GEN.getOrDefault(screen, 0) == gen;
	}

	/** Registers press and release handlers for this screen. Call on every screen init. */
	public static void register(Screen screen, int gen, Listener listener) {
		try {
			boolean ok = false;
			for (String name : new String[]{"allowMouseClick", "beforeMouseClick"}) {
				try {
					register(screen, name, name.substring(0, 1).toUpperCase() + name.substring(1), (proxy, method, args) -> {
						boolean allow = true;
						if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
						if (isCurrent(screen, gen) && args != null) {
							double[] xy = {Double.NaN, Double.NaN};
							int button = readMouse(args, xy);
							eventPresses++;
							if (dedupedPress(listener, button, xy[0], xy[1], true)) allow = false;
						}
						return method.getReturnType() == boolean.class ? (Object) allow : null;
					});
					ok = true;
					break;
				} catch (Throwable t) {
					Diagnostics.LOG.warn("[VinylMusicPlayer] Mouse press hook '{}' failed: {}", name, t.toString());
				}
			}
			pressHooked = pressHooked || ok;

			boolean rel = false;
			for (String name : new String[]{"beforeMouseRelease", "afterMouseRelease", "allowMouseRelease"}) {
				try {
					register(screen, name, name.substring(0, 1).toUpperCase() + name.substring(1), (proxy, method, args) -> {
						if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
						if (isCurrent(screen, gen) && args != null) {
							double[] xy = {Double.NaN, Double.NaN};
							int button = readMouse(args, xy);
							listener.release(button);
						}
						return method.getReturnType() == boolean.class ? (Object) Boolean.TRUE : null;
					});
					rel = true;
					break;
				} catch (Throwable t) {
					Diagnostics.LOG.warn("[VinylMusicPlayer] Mouse release hook '{}' failed: {}", name, t.toString());
				}
			}
			releaseHooked = releaseHooked || rel;
		} catch (Throwable t) {
			Diagnostics.LOG.error("[VinylMusicPlayer] Could not hook mouse input", t);
		}
	}

	private static void register(Screen screen, String factory, String ifaceName, InvocationHandler handler) throws Exception {
		Class<?> events = Class.forName(EVENTS);
		Object event = events.getMethod(factory, Screen.class).invoke(null, screen);
		Class<?> iface = Class.forName(EVENTS + "$" + ifaceName);
		Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
		Class.forName("net.fabricmc.fabric.api.event.Event").getMethod("register", Object.class).invoke(event, proxy);
	}

	private static Object objectMethod(Object proxy, Method method, Object[] args) {
		return switch (method.getName()) {
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == args[0];
			default -> "VinylMusicPlayer mouse hook";
		};
	}

	/** Reads x, y and button from either (screen, x, y, button) or (screen, MouseButtonEvent). */
	private static int readMouse(Object[] args, double[] xy) {
		int button = 0;
		int dbl = 0;
		for (int i = 1; i < args.length; i++) {
			Object a = args[i];
			if (a instanceof Double d) {
				if (dbl < 2) xy[dbl++] = d;
			} else if (a instanceof Integer n) {
				button = n;
			} else if (a != null) {
				try {
					xy[0] = ((Number) a.getClass().getMethod("x").invoke(a)).doubleValue();
					xy[1] = ((Number) a.getClass().getMethod("y").invoke(a)).doubleValue();
				} catch (Throwable ignored) {
				}
				try {
					button = ((Number) a.getClass().getMethod("button").invoke(a)).intValue();
				} catch (Throwable ignored) {
				}
			}
		}
		return button;
	}

	private static boolean dedupedPress(Listener l, int button, double x, double y, boolean fromEvent) {
		int b = Math.max(0, Math.min(2, button));
		long now = System.nanoTime();
		if (now - LAST_PRESS[b] < 250_000_000L) return false;
		LAST_PRESS[b] = now;
		return l.press(button, x, y);
	}

	/** Backup path: turns button state changes into presses and releases. Skips presses the events already gave. */
	public static void poll(Listener l, int mx, int my) {
		boolean left;
		boolean right;
		try {
			left = Input.leftDown();
			right = Input.rightDown();
		} catch (Throwable t) {
			return;
		}
		if (left && !prevLeft) {
			long before = LAST_PRESS[0];
			if (dedupedPress(l, 0, mx, my, false) || LAST_PRESS[0] != before) pollPresses++;
		}
		if (!left && prevLeft) l.release(0);
		if (right && !prevRight) {
			long before = LAST_PRESS[1];
			if (dedupedPress(l, 1, mx, my, false) || LAST_PRESS[1] != before) pollPresses++;
		}
		if (!right && prevRight) l.release(1);
		prevLeft = left;
		prevRight = right;
	}
}
