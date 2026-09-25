package xen.mod.client;

import net.minecraft.ChatFormatting;
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
 * Xen's settings, from Mod Menu, in tabs: Talk, Xens, Goals, PvP, Build and Speed. Each tab says what it's about, its
 * settings sit in a grid (hover one to read what it does), "Reset tab" puts that tab back to how it comes, and Done
 * saves to config/xen.json. Changes apply right away in single player (on a server, operators use /xen set).
 */
public class XenSettingsScreen extends Screen {
	private static final String[] TABS = {"Talk", "Xens", "Goals", "PvP", "Build", "Speed"};
	private static final String[] ABOUT = {
			"How Xen talks: answering, talking on its own, with other Xens, trading, saying no.",
			"Who the Xens are: how many, their names, natures and skins.",
			"What Xens want and how they grow: their own goals, learning and evolution.",
			"Teams and fighting players.",
			"Building: redstone circuits and notes on signs.",
			"For slower computers and phones.",
	};
	/** The tab that's open (kept while the game runs, so it opens where you left it). */
	private static int tab;

	private final Screen parent;
	private final XenSettings settings = XenSettings.get();
	private final List<String> keys = new ArrayList<>();
	private int row, buttonWidth, top;

	public XenSettingsScreen(Screen parent) {
		super(Component.literal("Xen Companion"));
		this.parent = parent;
	}

	/** Open on a tab (for the screenshot test). */
	static XenSettingsScreen onTab(Screen parent, int which) {
		tab = Math.floorMod(which, TABS.length);
		return new XenSettingsScreen(parent);
	}

	@Override
	protected void init() {
		row = 0;
		keys.clear();
		Component title = Component.literal("Xen Companion").withStyle(ChatFormatting.BOLD);
		addRenderableWidget(new StringWidget((width - font.width(title)) / 2, 8, font.width(title), 12, title, font));

		int tabWidth = Math.min(64, (width - 16) / TABS.length - 4), tabsWide = TABS.length * (tabWidth + 4) - 4;
		for (int i = 0; i < TABS.length; i++) {
			int which = i;
			Button b = Button.builder(Component.literal(TABS[i]), x -> {
				tab = which;
				rebuildWidgets();
			}).bounds((width - tabsWide) / 2 + i * (tabWidth + 4), 24, tabWidth, 20).build();
			b.active = i != tab;                                     // the open tab is the pressed-in one
			addRenderableWidget(b);
		}
		Component about = Component.literal(ABOUT[tab]).withStyle(ChatFormatting.GRAY);
		int aboutWidth = Math.min(font.width(about), width - 16);
		addRenderableWidget(new StringWidget((width - aboutWidth) / 2, 50, aboutWidth, 12, about, font).setMaxWidth(width - 16));

		top = 66;
		buttonWidth = Math.min(160, (width - 24) / 2);
		switch (tab) {
			case 0 -> {
				onOff("Chat", "chat", "Xen answers and understands chat.");
				choice("Chat model", "chatModel", List.of("auto", "on", "off"), s -> s,
						"The small chat model: auto = only with about 3 GB of memory for the game. It only wakes when someone Xen knows is near or talks.");
				choice("Chat on GPU", "gpu", List.of("auto", "on", "off"), s -> s,
						"Run the chat model on the graphics card (much faster answers). auto: when there's a real graphics card with OpenGL 3.3, in single player. Works with Sodium, Iris and Vulkan mods. Applies the next time the model loads.");
				onOff("Talks on its own", "talk", "Xen says what's on its mind now and then, greets people it knows and asks things you can answer with yes or no.");
				onOff("Talks with Xens", "talkToXens", "Xens that meet have a short chat and tell each other where they saw trees and ore.");
				onOff("Trading", "trading", "Xen trades with villagers (on their trading screen) and bargains with players.");
				onOff("Can say no", "refuse", "Xen may refuse: when it's badly hurt, scared, needs what you ask for, or you hurt it. Saying please changes its mind (unless you hurt it).");
			}
			case 1 -> {
				choice("Xens per player", "maxPerPlayer", List.of(1, 2, 3, 5, 10, 0), n -> n == 0 ? "no limit" : "" + n, "How many Xens one player may summon.");
				choice("Xens in the world", "maxXens", List.of(0, 5, 10, 20, 50, 100), n -> n == 0 ? "no limit" : "" + n, "How many Xens the world may have in all.");
				onOff("Random names", "randomNames", "New Xens get names like Pip, Nova or Bramble.");
				onOff("Personalities", "personalities", "Each Xen is braver or more timid, curious, chatty or quiet, patient, and has its own tone.");
				choice("Skins", "skins", skinChoices(), s -> s, "Built-in skins (every game has them). Custom skins: add texture:<value>:<signature> from mineskin.org in config/xen.json.");
			}
			case 2 -> {
				onOff("Own goals", "wants", "Free Xens choose their own goals (food, shelter, wood, stone, ore, trading, exploring) and a dream to work toward, and learn which they like.");
				onOff("Learning", "learn", "Xen keeps learning from what it lives through.");
				onOff("Learns by watching", "copy", "Xen watches you and copies moves that work out for you: the water-bucket clutch, and how you fight when you win. Clumsy at first, better each time. Say \"Pip, watch this\" first.");
				onOff("Evolution", "evolution", "Every few days the ownerless Xens that did worst are replaced by children of the best.");
				choice("Days per generation", "generationDays", List.of(1, 2, 3, 5, 7, 10), n -> "" + n, "How often evolution happens (Minecraft days).");
			}
			case 3 -> {
				choice("Teams", "teams", List.of(0, 1, 2, 3, 4, 6), n -> n == 0 ? "none" : n == 1 ? "one team" : n + " teams", "Put Xens on teams (no friendly fire).");
				choice("PvP", "pvp", List.of("off", "defend", "teams"), s -> s,
						"defend: fights back against players who hurt it or its owner. teams: Xens of different teams fight too.");
			}
			case 4 -> {
				onOff("Redstone", "redstone", "Xen may build small circuits it learned (NOT, OR, AND gates) from parts it carries.");
				choice("Biggest circuit", "maxRedstoneParts", List.of(8, 16, 24, 32, 64), n -> n + " parts", "Nothing bigger, so it can't slow the server down.");
				onOff("Signs", "signs", "When nobody it knows is around, Xen leaves notes on signs it carries.");
			}
			default -> {
				choice("Decisions", "decisionTicks", List.of(5, 10, 20), n -> n == 5 ? "4 a second" : n == 10 ? "2 a second" : "1 a second",
						"Fewer decisions for slower computers and phones.");
				choice("Chat threads", "chatThreads", List.of(1, 2, 3, 4, 6, 8), n -> n + (n == 1 ? " thread" : " threads"),
						"How many processor cores the chat model may use (applies the next time it loads).");
			}
		}

		int y = Math.max(top + ((row + 1) / 2) * 24 + 8, height - 28);
		int half = Math.min(100, (width - 24) / 2);
		addRenderableWidget(Button.builder(Component.literal("Reset tab"), b -> {
			settings.reset(new ArrayList<>(keys));
			rebuildWidgets();
		}).bounds(width / 2 - half - 4, y, half, 20).tooltip(Tooltip.create(Component.literal("Put this tab's settings back to how they come."))).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 + 4, y, half, 20).build());
	}

	private List<String> skinChoices() {
		List<String> out = new ArrayList<>(List.of("random", "steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"));
		String now = String.join(",", settings.skins());
		if (!out.contains(now)) out.add(0, now);
		return out;
	}

	/** Two columns, centered. */
	private int[] next() {
		int col = row % 2, line = row / 2;
		row++;
		int total = 2 * buttonWidth + 8;
		return new int[] {(width - total) / 2 + col * (buttonWidth + 8), top + line * 24};
	}

	private void onOff(String label, String key, String help) {
		keys.add(key);
		int[] at = next();
		addRenderableWidget(CycleButton.onOffBuilder(Boolean.parseBoolean(settings.value(key)))
				.create(at[0], at[1], buttonWidth, 20, Component.literal(label), (b, v) -> settings.set(key, "" + v)))
				.setTooltip(Tooltip.create(Component.literal(help)));
	}

	private <T> void choice(String label, String key, List<T> values, java.util.function.Function<T, String> show, String help) {
		keys.add(key);
		int[] at = next();
		String now = key.equals("skins") ? String.join(",", settings.skins()) : settings.value(key);
		List<T> options = new ArrayList<>(values);
		T current = null;
		for (T v : options) if (v.toString().equals(now)) current = v;
		if (current == null) {                                            // a value set by hand (in xen.json or /xen set): keep it
			try {
				@SuppressWarnings("unchecked")
				T own = (T) (options.get(0) instanceof Integer ? (Object) Integer.valueOf(now) : now);
				options.add(0, own);
				current = own;
			} catch (RuntimeException e) {
				current = options.get(0);
			}
		}
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
