package xen.mod;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

/** The few calls that differ between Minecraft versions (this is the 1.21.11 side). */
final class Compat {
	private Compat() {}

	static void swing(LivingEntity e) {
		e.swing(InteractionHand.MAIN_HAND);
	}
}
