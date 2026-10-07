package xen.mod.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import xen.mod.BuildAxe;

/**
 * The Build Axe's screen, when both corners are marked: the box's size and what's left out of it, what it is (Build,
 * Tree, Cave, or a type of the player's own: "house", "farm", "bridge"), its name, and Save (with a type and a name) or
 * Cancel. Saving sends the type and name; the server writes the line. Cancel keeps the box (right-click blocks to leave
 * them out, then right-click into the air with the axe for this screen again).
 */
public final class BuildAxeScreen extends Screen {
	private static final String[] PRESETS = {"build", "tree", "cave"};
	/** The type and name typed last (the next box is often another of the same: "oak 2" after "oak 1"). */
	private static String lastName = "", lastKind = "";

	private final BuildAxe.Open box;
	private EditBox kind, name;
	private Button save;

	public BuildAxeScreen(BuildAxe.Open box) {
		super(Component.literal("Build Axe"));
		this.box = box;
	}

	@Override
	protected void init() {
		int w = Math.min(240, width - 20), x = (width - w) / 2, y = Math.max(6, height / 2 - 84);
		addRenderableWidget(new StringWidget(x, y, w, 12, Component.literal("Build Axe").withStyle(ChatFormatting.GOLD), font));
		String left = box.left() == 0 && box.leftKinds().isEmpty() ? "nothing left out"
				: "left out: " + (box.left() > 0 ? box.left() + (box.left() == 1 ? " block" : " blocks") : "")
						+ (box.leftKinds().isEmpty() ? "" : (box.left() > 0 ? ", " : "") + "every " + box.leftKinds());
		addRenderableWidget(new StringWidget(x, y + 13, w, 12, Component.literal(box.sx() + " x " + box.sy() + " x " + box.sz() + " blocks, " + left)
				.withStyle(ChatFormatting.GRAY), font));
		int third = (w - 8) / 3;
		for (int i = 0; i < PRESETS.length; i++) {
			String k = PRESETS[i];
			addRenderableWidget(Button.builder(Component.literal(k.substring(0, 1).toUpperCase() + k.substring(1)), b -> {
				kind.setValue(k);
				setFocused(name);
			}).bounds(x + i * (third + 4), y + 30, third, 20).build());
		}
		kind = new EditBox(font, x, y + 54, w, 20, Component.literal("Type"));
		kind.setMaxLength(32);
		kind.setHint(Component.literal("Type: build, tree, cave, or your own").withStyle(ChatFormatting.DARK_GRAY));
		kind.setValue(!lastKind.isEmpty() ? lastKind : box.kind());
		kind.setResponder(v -> ready());
		addRenderableWidget(kind);
		name = new EditBox(font, x, y + 80, w, 20, Component.literal("Name"));
		name.setMaxLength(64);
		name.setHint(Component.literal("Its name (a must)").withStyle(ChatFormatting.DARK_GRAY));
		name.setValue(lastName);
		name.setResponder(v -> ready());
		addRenderableWidget(name);
		int half = (w - 4) / 2;
		save = addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
			lastName = name.getValue().trim();
			lastKind = kind.getValue().trim();
			ClientPlayNetworking.send(new BuildAxe.Save(lastKind, lastName));
			onClose();
		}).bounds(x, y + 106, half, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x + w - half, y + 106, half, 20).build());
		ready();
		String[] help = {"Right-click a block: leave it out (as air).", "Crouch + right-click: all of that kind.", "Right-click the air: this screen again."};
		for (int i = 0; i < help.length; i++) addRenderableWidget(new StringWidget(x, y + 130 + 11 * i, w, 10, Component.literal(help[i]).withStyle(ChatFormatting.DARK_GRAY), font));
		setInitialFocus(name);
	}

	/** Save only with a type and a name. */
	private void ready() {
		if (save != null) save.active = !name.getValue().isBlank() && !kind.getValue().isBlank();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
