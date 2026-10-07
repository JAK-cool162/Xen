package xen.mod.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import xen.mod.BuildAxe;

import java.util.List;

/**
 * The Build Axe's screen, when both corners are marked: the box's size, what it is (a build, a tree, a cave), its
 * name, and Save (only with a name) or Cancel. Saving sends the kind and name; the server writes the line. Cancel keeps
 * the corners (move one and the screen comes back, or right-click into the air with the axe).
 */
public final class BuildAxeScreen extends Screen {
	private static final List<String> KINDS = List.of("build", "tree", "cave");
	/** The name typed last (the next box is often another of the same: "oak 2" after "oak 1"). */
	private static String lastName = "";

	private final int sx, sy, sz;
	private String kind;
	private EditBox name;
	private Button save;

	public BuildAxeScreen(BuildAxe.Open box) {
		super(Component.literal("Build Axe"));
		sx = box.sx();
		sy = box.sy();
		sz = box.sz();
		kind = KINDS.contains(box.kind()) ? box.kind() : "build";
	}

	@Override
	protected void init() {
		int w = Math.min(220, width - 20), x = (width - w) / 2, y = Math.max(10, height / 2 - 62);
		addRenderableWidget(new StringWidget(x, y, w, 12, Component.literal("Build Axe").withStyle(ChatFormatting.GOLD), font));
		addRenderableWidget(new StringWidget(x, y + 14, w, 12, Component.literal(sx + " x " + sy + " x " + sz + " blocks marked").withStyle(ChatFormatting.GRAY), font));
		addRenderableWidget(CycleButton.builder((String v) -> Component.literal(v.substring(0, 1).toUpperCase() + v.substring(1)), kind).withValues(KINDS)
				.create(x, y + 32, w, 20, Component.literal("It's a"), (b, v) -> kind = v));
		name = new EditBox(font, x, y + 60, w, 20, Component.literal("Name"));
		name.setMaxLength(64);
		name.setHint(Component.literal("Its name (a must)").withStyle(ChatFormatting.DARK_GRAY));
		name.setValue(lastName);
		name.setResponder(v -> save.active = !v.isBlank());
		addRenderableWidget(name);
		int half = (w - 4) / 2;
		save = addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
			lastName = name.getValue().trim();
			ClientPlayNetworking.send(new BuildAxe.Save(kind, lastName));
			onClose();
		}).bounds(x, y + 88, half, 20).build());
		save.active = !name.getValue().isBlank();
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x + w - half, y + 88, half, 20).build());
		addRenderableWidget(new StringWidget(x, y + 114, w, 12, Component.literal("Saved to config/xen/buildaxe/<kind>.jsonl").withStyle(ChatFormatting.DARK_GRAY), font));
		setInitialFocus(name);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
