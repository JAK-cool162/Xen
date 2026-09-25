package xen.mod;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.scores.PlayerTeam;

import java.util.List;

/** The few calls that differ between Minecraft versions (this is the 1.21.11 side). */
final class Compat {
	private Compat() {}

	/** The time of day, 0 to 23999 (0 is sunrise, 13000 about nightfall). */
	static long timeOfDay(net.minecraft.world.level.Level level) {
		return level.getDayTime() % 24000;
	}

	static void swing(LivingEntity e) {
		e.swing(InteractionHand.MAIN_HAND);
	}

	/** Is it swinging its arm (it can see that)? */
	static boolean swinging(LivingEntity e) {
		return e.swinging;
	}

	/** Tosses a stack like the Q key. */
	static boolean drop(ServerPlayer p, ItemStack stack) {
		p.drop(stack, false, true);
		return true;
	}

	/** Writes 4 lines on the front of a sign. */
	static boolean signText(SignBlockEntity sign, List<Component> lines) {
		SignText text = new SignText();
		for (int i = 0; i < lines.size(); i++) text = text.setMessage(i, lines.get(i));
		sign.setText(text, true);
		return true;
	}

	/** Right-click an entity, like a player (a villager opens its trades). */
	static void interact(ServerPlayer p, net.minecraft.world.entity.Entity e) {
		p.interactOn(e, InteractionHand.MAIN_HAND);
	}

	/** Click a slot of the open screen (shift-click with quick), like a player's mouse. */
	static void click(net.minecraft.world.inventory.AbstractContainerMenu menu, int slot, boolean quick, ServerPlayer p) {
		menu.clicked(slot, 0, quick ? net.minecraft.world.inventory.ClickType.QUICK_MOVE : net.minecraft.world.inventory.ClickType.PICKUP, p);
	}

	static void teamColor(PlayerTeam team, ChatFormatting color) {
		team.setColor(color);
	}
}
