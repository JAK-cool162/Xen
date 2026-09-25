package xen.mod;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.scores.PlayerTeam;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

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

	private static java.lang.reflect.Method isSwinging;
	private static java.lang.reflect.Field swinging;

	static {
		try {
			isSwinging = LivingEntity.class.getMethod("isSwinging");                        // 26.3
		} catch (NoSuchMethodException e) {
			try {
				swinging = LivingEntity.class.getField("swinging");                         // 26.1
			} catch (NoSuchFieldException e2) {
				swinging = null;
			}
		}
	}

	private Compat() {}

	/** Is it swinging its arm (it can see that)? */
	static boolean swinging(LivingEntity e) {
		try {
			if (isSwinging != null) return (boolean) isSwinging.invoke(e);
			return swinging != null && swinging.getBoolean(e);
		} catch (ReflectiveOperationException ex) {
			return false;
		}
	}

	static void swing(LivingEntity e) {
		if (swing == null) return;
		try {
			swing.invoke(e, swingArgs);
		} catch (ReflectiveOperationException ignored) {
			// only the arm animation; nothing depends on it
		}
	}

	/** Tosses a stack like the Q key. 26.1: drop(stack, false, true); 26.3: drop(stack, true, Prediction.PREDICTED). */
	static boolean drop(ServerPlayer p, ItemStack stack) {
		try {
			try {
				ServerPlayer.class.getMethod("drop", ItemStack.class, boolean.class, boolean.class).invoke(p, stack, false, true);
			} catch (NoSuchMethodException e) {
				Class<?> prediction = Class.forName("net.minecraft.util.Prediction");
				ServerPlayer.class.getMethod("drop", ItemStack.class, boolean.class, prediction)
						.invoke(p, stack, true, prediction.getField("PREDICTED").get(null));
			}
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			XenMod.LOG.warn("Could not toss {}: {}", stack, e.toString());
			return false;
		}
	}

	/**
	 * Writes 4 lines on the front of a sign. 26.1: SignText(Component[], ...) and setText(text, true);
	 * 26.3: SignText(List, ...) and setText(text, SignTextSlot.FRONT).
	 */
	static boolean signText(SignBlockEntity sign, List<Component> lines) {
		try {
			Object text;
			try {
				text = SignText.class.getConstructor(List.class, List.class, DyeColor.class, boolean.class)
						.newInstance(lines, lines, DyeColor.BLACK, false);
			} catch (NoSuchMethodException e) {
				Component[] a = lines.toArray(new Component[0]);
				text = SignText.class.getConstructor(Component[].class, Component[].class, DyeColor.class, boolean.class)
						.newInstance(a, a.clone(), DyeColor.BLACK, false);
			}
			try {
				Class<?> slot = Class.forName("net.minecraft.world.level.block.entity.SignTextSlot");
				SignBlockEntity.class.getMethod("setText", SignText.class, slot).invoke(sign, text, slot.getField("FRONT").get(null));
			} catch (ClassNotFoundException e) {
				SignBlockEntity.class.getMethod("setText", SignText.class, boolean.class).invoke(sign, text, true);
			}
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			XenMod.LOG.warn("Could not write on a sign: {}", e.toString());
			return false;
		}
	}

	/** A team's color (only looks). 26.1: a ChatFormatting; 26.3: an Optional TeamColor with the same names. */
	static void teamColor(PlayerTeam team, ChatFormatting color) {
		try {
			for (Method m : PlayerTeam.class.getMethods()) {
				if (!m.getName().equals("setColor") || m.getParameterCount() != 1) continue;
				Class<?> type = m.getParameterTypes()[0];
				if (type == ChatFormatting.class) {
					m.invoke(team, color);
				} else if (type == Optional.class) {
					Class<?> teamColor = Class.forName("net.minecraft.world.scores.TeamColor");
					m.invoke(team, Optional.of(teamColor.getMethod("valueOf", String.class).invoke(null, color.name())));
				}
				return;
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// still a team, just without a color
		}
	}
}
