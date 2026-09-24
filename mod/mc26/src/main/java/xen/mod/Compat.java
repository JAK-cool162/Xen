package xen.mod;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Method;

/**
 * The few calls that differ between Minecraft versions (this is the 26.x side).
 * 26.x is unobfuscated, so one jar can look up whichever form this 26.x has.
 */
final class Compat {
	private static Method swing;
	private static Object[] swingArgs;

	static {
		try {
			swing = LivingEntity.class.getMethod("swing", InteractionHand.class);            // 26.1
			swingArgs = new Object[] {InteractionHand.MAIN_HAND};
		} catch (NoSuchMethodException e) {
			try {                                                                            // 26.2+: (hand, animation, ...)
				Class<?> anim = Class.forName("net.minecraft.world.item.component.SwingAnimation");
				swing = LivingEntity.class.getMethod("swing", InteractionHand.class, anim, boolean.class);
				swingArgs = new Object[] {InteractionHand.MAIN_HAND, anim.getField("DEFAULT").get(null), true};
			} catch (ReflectiveOperationException e2) {
				swing = null;
			}
		}
	}

	private Compat() {}

	static void swing(LivingEntity e) {
		if (swing == null) return;
		try {
			swing.invoke(e, swingArgs);
		} catch (ReflectiveOperationException ignored) {
			// only the arm animation; nothing depends on it
		}
	}
}
