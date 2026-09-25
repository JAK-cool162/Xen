package xen.mod.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import xen.mod.XenSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Xen's settings, from Mod Menu: how many Xens, names, personalities and skins, teams and PvP, evolution, redstone,
 * chat. Changes are saved to config/xen.json and apply right away in single player (on a server, operators use
 * /xen set).
 */
public class XenSettingsScreen extends Screen {
	private final Screen parent;
	private final XenSettings settings = XenSettings.get();
	private int row, columns, buttonWidth, rowHeight, top;
	private static final int OPTIONS = 16;

	public XenSettingsScreen(Screen parent) {
		super(Component.literal("Xen Companion"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		row = 0;
		// Fit any screen (phones with a big interface scale too): 2 columns, or 3 when it's short, never overlapping.
		top = 30;
		rowHeight = 24;
		columns = 2;
		while (top + ((OPTIONS + columns - 1) / columns) * rowHeight + 30 > height && rowHeight > 21) rowHeight--;
		if (top + ((OPTIONS + columns - 1) / columns) * rowHeight + 30 > height) columns = 3;
		buttonWidth = Math.min(150, (width - 20) / columns - 6);
		Component title = Component.literal("Xen Companion settings");
		addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 10, font.width(title), 12, title, font));
		onOff("Chat", "chat", "Xen answers and understands chat.");
		choice("Chat model", "chatModel", List.of("auto", "on", "off"), s -> s,
				"The small chat model: auto = only with about 3 GB of memory for the game. It only wakes when someone Xen knows is near or talks.");
		onOff("Learning", "learn", "Xen keeps learning from what it lives through.");
		choice("Xens per player", "maxPerPlayer", List.of(1, 2, 3, 5, 10, 0), n -> n == 0 ? "no limit" : "" + n, "How many Xens one player may summon.");
		choice("Xens in the world", "maxXens", List.of(0, 5, 10, 20, 50, 100), n -> n == 0 ? "no limit" : "" + n, "How many Xens the world may have in all.");
		onOff("Random names", "randomNames", "New Xens get names like Pip, Nova or Bramble.");
		onOff("Personalities", "personalities", "Each Xen is braver or more timid, curious, chatty or quiet, patient, and has its own tone.");
		choice("Skins", "skins", skinChoices(), s -> s, "Built-in skins (every game has them). Custom skins: add texture:<value>:<signature> from mineskin.org in config/xen.json.");
		choice("Teams", "teams", List.of(0, 1, 2, 3, 4, 6), n -> n == 0 ? "none" : n == 1 ? "one team" : n + " teams", "Put Xens on teams (no friendly fire).");
		choice("PvP", "pvp", List.of("off", "defend", "teams"), s -> s,
				"defend: fights back against players who hurt it or its owner. teams: Xens of different teams fight too.");
		onOff("Evolution", "evolution", "Every few days the ownerless Xens that did worst are replaced by children of the best.");
		choice("Days per generation", "generationDays", List.of(1, 2, 3, 5, 7, 10), n -> "" + n, "How often evolution happens (Minecraft days).");
		onOff("Redstone", "redstone", "Xen may build small circuits it learned (NOT, OR, AND gates) from parts it carries.");
		choice("Biggest circuit", "maxRedstoneParts", List.of(8, 16, 24, 32, 64), n -> n + " parts", "Nothing bigger, so it can't slow the server down.");
		onOff("Signs", "signs", "When nobody it knows is around, Xen leaves notes on signs it carries.");
		choice("Decisions", "decisionTicks", List.of(5, 10, 20), n -> n == 5 ? "4 a second" : n == 10 ? "2 a second" : "1 a second",
				"Fewer decisions for slower computers and phones.");
		int y = top + ((row + columns - 1) / columns) * rowHeight + 4;
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, y, 200, 20).build());
	}

	private List<String> skinChoices() {
		List<String> out = new ArrayList<>(List.of("random", "steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"));
		String now = String.join(",", settings.skins());
		if (!out.contains(now)) out.add(0, now);
		return out;
	}

	private int[] next() {
		int col = row % columns, line = row / columns;
		row++;
		int total = columns * (buttonWidth + 6) - 6;
		return new int[] {(width - total) / 2 + col * (buttonWidth + 6), top + line * rowHeight};
	}

	private void onOff(String label, String key, String help) {
		int[] at = next();
		addRenderableWidget(CycleButton.onOffBuilder(Boolean.parseBoolean(settings.value(key)))
				.create(at[0], at[1], buttonWidth, 20, Component.literal(label), (b, v) -> settings.set(key, "" + v)))
				.setTooltip(Tooltip.create(Component.literal(help)));
	}

	private <T> void choice(String label, String key, List<T> values, java.util.function.Function<T, String> show, String help) {
		int[] at = next();
		String now = key.equals("skins") ? String.join(",", settings.skins()) : settings.value(key);
		List<T> options = new ArrayList<>(values);
		T current = options.get(0);
		for (T v : options) if (v.toString().equals(now)) current = v;
		addRenderableWidget(CycleButton.builder((T v) -> Component.literal(show.apply(v)), current).withValues(options)
				.create(at[0], at[1], buttonWidth, 20, Component.literal(label), (b, v) -> settings.set(key, v.toString())))
				.setTooltip(Tooltip.create(Component.literal(help)));
	}

	@Override
	public void onClose() {
		settings.save();
		Screens.open(minecraft, parent);
	}
}
