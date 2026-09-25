package xen.mod.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import xen.mod.XenSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Xen's settings, from Mod Menu. The categories are down the left side (Talk, Xens, Goals, PvP, Build, Speed and
 * Experimental); the open one's settings are on the right (hover one to read what it does). Experimental has two
 * text boxes: custom instructions (what Xens know about themselves) and a custom script (rules like "when night: do
 * build a shelter"). "Reset" puts the open category back to how it comes, and Done saves to config/xen.json. Changes
 * apply right away in single player (on a server, operators use /xen set).
 */
public class XenSettingsScreen extends Screen {
	private static final String[] TABS = {"Talk", "Xens", "Goals", "PvP", "Build", "Speed", "Experimental"};
	private static final String[] ABOUT = {
			"How Xen talks: answering, talking on its own, with other Xens, trading, saying no.",
			"Who the Xens are: how many, their names, natures and skins.",
			"What Xens want and how they grow: goals, fun, learning and evolution.",
			"Teams and fighting players.",
			"Building: redstone circuits and notes on signs.",
			"For slower computers and phones.",
			"Tell Xens who they are, and write your own rules for them.",
	};
	private static final String SCRIPT_HELP = "One rule per line: when <something happens>: <what to do>. Lines starting with # are notes.\n"
			+ "When: night, morning, rain, hungry, hurt, attacked, diamonds, someone comes, sees <a mob>, hears <a word>, every <N> minutes.\n"
			+ "Do: say <words> ({name} and {player} are filled in), do <a request> (as if its owner asked: \"do follow me\", "
			+ "\"do get 5 wood\"), dance, spin, wave, show off.\nEach rule runs at most once a minute.";
	/** The category that's open (kept while the game runs, so it opens where you left it). */
	private static int tab;
	/** No tooltips (the screenshot test: they'd cover the screen). */
	static boolean noTooltips;

	private final Screen parent;
	private final XenSettings settings = XenSettings.get();
	private final List<String> keys = new ArrayList<>();
	private int row, columns, buttonWidth, left, panelWidth, top;
	private StringWidget scriptStatus;

	public XenSettingsScreen(Screen parent) {
		super(Component.literal("Xen Companion"));
		this.parent = parent;
	}

	/** Open on a category (for the screenshot test). */
	static XenSettingsScreen onTab(Screen parent, int which) {
		tab = Math.floorMod(which, TABS.length);
		return new XenSettingsScreen(parent);
	}

	/** How many categories there are. */
	static int tabs() {
		return TABS.length;
	}

	@Override
	protected void init() {
		row = 0;
		keys.clear();
		scriptStatus = null;
		// the categories, down the left side
		int side = Math.max(80, Math.min(110, width / 5));
		Component title = Component.literal("Xen").withStyle(ChatFormatting.BOLD, ChatFormatting.AQUA);
		addRenderableWidget(new StringWidget(8, 10, side - 8, 12, title, font));
		for (int i = 0; i < TABS.length; i++) {
			int which = i;
			Button b = Button.builder(Component.literal(TABS[i]), x -> {
				tab = which;
				rebuildWidgets();
			}).bounds(8, 28 + i * 22, side - 8, 20).tooltip(tip(Component.literal(ABOUT[i]))).build();
			b.active = i != tab;                                         // the open one is the pressed-in one
			addRenderableWidget(b);
		}

		// the open category, on the right
		left = side + 16;
		panelWidth = width - left - 10;
		Component heading = Component.literal(TABS[tab]).withStyle(ChatFormatting.BOLD);
		addRenderableWidget(new StringWidget(left, 10, panelWidth, 12, heading, font));
		Component about = Component.literal(ABOUT[tab]).withStyle(ChatFormatting.GRAY);
		addRenderableWidget(new StringWidget(left, 24, panelWidth, 12, about, font).setMaxWidth(panelWidth));
		top = 42;
		columns = panelWidth >= 330 ? 2 : 1;
		buttonWidth = columns == 2 ? Math.min(180, (panelWidth - 8) / 2) : Math.min(240, panelWidth);

		switch (tab) {
			case 0 -> {
				onOff("Chat", "chat", "Xen answers and understands chat.");
				choice("Chat model", "chatModel", List.of("auto", "on", "off"), s -> s,
						"The small chat model: auto = only with about 3 GB of memory for the game. It only wakes when someone Xen knows is near or talks. Without it, Xens still understand requests and answer simply.");
				onOff("Talks on its own", "talk", "Xen says what's on its mind now and then, greets people it knows and asks things you can answer with yes or no.");
				onOff("Talks with Xens", "talkToXens", "Xens that meet have a short chat and tell each other where they saw trees and ore.");
				onOff("Trading", "trading", "Xen trades with villagers (on their trading screen) and bargains with players.");
				onOff("Can say no", "refuse", "Xen may refuse: when it's badly hurt, scared, needs what you ask for, or you hurt it. Saying please changes its mind (unless you hurt it).");
			}
			case 1 -> {
				choice("Xens per player", "maxPerPlayer", List.of(1, 2, 3, 5, 10, 0), n -> n == 0 ? "no limit" : "" + n, "How many Xens one player may summon.");
				choice("Xens in the world", "maxXens", List.of(0, 5, 10, 20, 50, 100), n -> n == 0 ? "no limit" : "" + n, "How many Xens the world may have in all.");
				onOff("Random names", "randomNames", "New Xens get names that fit their nature (off: Xen, Xen2, Xen3...).");
				choice("Name style", "nameStyle", List.of("mixed", "fun", "gamer", "fantasy", "classic"), s -> s,
						"mixed: all kinds. fun: SneakyWaffle, GrumpyBadger. gamer: Pickle_42, xXWaffleXx. fantasy: Zorbax, Lumika. classic: Pip, Bramble.");
				onOff("Personalities", "personalities", "Each Xen is braver or more timid, curious, chatty or quiet, patient, and has its own tone.");
				choice("Skins", "skins", skinChoices(), s -> s,
						"random: the mod's 61 skins and Minecraft's 18. pack: only the mod's. folder: your own PNG skins in config/xen/skins (from NameMC, Planet Minecraft or drawn yourself; signed once through mineskin.org, so everyone sees them). mineskin: random skins from mineskin.org's gallery (online). Or a player's skin: /xen set skins player:Name.");
			}
			case 2 -> {
				onOff("Own goals", "wants", "Free Xens choose their own goals (food, shelter, wood, stone, ore, trading, exploring) and a dream to work toward, and learn which they like.");
				onOff("Antics", "antics", "Xen does unpredictable things for fun: dances along when you crouch-dance, shows off tricks (that don't always work), surprises in fights. Playful Xens more.");
				onOff("Learns by watching", "copy", "Xen watches you and copies moves that work out for you: the water-bucket clutch, and how you fight when you win. Clumsy at first, better each time. Say \"Pip, watch this\" first.");
				onOff("Learning", "learn", "Xen keeps learning from what it lives through.");
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
			case 5 -> {
				choice("Chat on GPU", "gpu", List.of("auto", "on", "off"), s -> s,
						"Run the chat model on the graphics card (much faster answers). auto: when there's a real graphics card with OpenGL 3.3, in single player. Works with Sodium, Iris and Vulkan mods. Applies the next time the model loads.");
				choice("Decisions", "decisionTicks", List.of(5, 10, 20), n -> n == 5 ? "4 a second" : n == 10 ? "2 a second" : "1 a second",
						"Fewer decisions for slower computers and phones.");
				choice("Chat threads", "chatThreads", List.of(1, 2, 3, 4, 6, 8), n -> n + (n == 1 ? " thread" : " threads"),
						"How many processor cores the chat model may use (applies the next time it loads).");
			}
			default -> experimental();
		}

		int half = Math.min(90, (panelWidth - 8) / 2);
		int y = height - 28;
		addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
			settings.reset(new ArrayList<>(keys));
			rebuildWidgets();
		}).bounds(width - 10 - 2 * half - 8, y, half, 20).tooltip(tip(Component.literal("Put " + TABS[tab] + "'s settings back to how they come."))).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width - 10 - half, y, half, 20).build());
	}

	/** Custom instructions and the custom script: two text boxes. */
	private void experimental() {
		keys.add("instructions");
		keys.add("script");
		int bottom = height - 36, label = 12;
		int free = Math.max(80, bottom - top - 3 * label - 8);
		int instructionsHeight = Math.max(36, free / 3), scriptHeight = Math.max(44, free - instructionsHeight);
		int y = top;

		addRenderableWidget(new StringWidget(left, y, panelWidth, label, Component.literal("Custom instructions"), font));
		y += label;
		MultiLineEditBox instructions = MultiLineEditBox.builder().setX(left).setY(y)
				.setPlaceholder(Component.literal("What Xens know about themselves, for example:\nYou love cats and hate the rain. You're scared of the dark.\nPip: you're a pirate and talk like one.")
						.withStyle(ChatFormatting.DARK_GRAY))
				.build(font, panelWidth, instructionsHeight, Component.literal("Custom instructions"));
		instructions.setCharacterLimit(1000);
		instructions.setValue(settings.value("instructions"));
		instructions.setValueListener(v -> settings.set("instructions", v));
		instructions.setTooltip(tip(Component.literal("Every Xen's personality and what it should know. A line that starts with a Xen's name and a colon "
				+ "(\"Pip: ...\") is only for that Xen. The chat model reads it when it answers and talks.")));
		addRenderableWidget(instructions);
		y += instructionsHeight + 6;

		addRenderableWidget(new StringWidget(left, y, panelWidth, label, Component.literal("Custom script"), font));
		y += label;
		MultiLineEditBox script = MultiLineEditBox.builder().setX(left).setY(y)
				.setPlaceholder(Component.literal("when night: do build a shelter\nwhen morning: say Good morning, {player}!\nwhen someone comes: wave\n"
						+ "when hears hello: dance\nwhen sees creeper: say RUN!\nwhen every 10 minutes: show off").withStyle(ChatFormatting.DARK_GRAY))
				.build(font, panelWidth, scriptHeight, Component.literal("Custom script"));
		script.setCharacterLimit(2000);
		script.setValue(settings.value("script"));
		script.setTooltip(tip(Component.literal(SCRIPT_HELP)));
		addRenderableWidget(script);
		y += scriptHeight + 2;

		scriptStatus = new StringWidget(left, y, panelWidth, label, Component.empty(), font);
		addRenderableWidget(scriptStatus);
		showScriptStatus(script.getValue());
		script.setValueListener(v -> {
			settings.set("script", v);
			showScriptStatus(v);
		});
	}

	/** Under the script box: how it reads (lines it can't read in red). */
	private void showScriptStatus(String script) {
		if (scriptStatus == null) return;
		List<String> problems = XenSettings.scriptProblems(script);
		long rules = script.lines().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).count() - problems.size();
		Component text = !problems.isEmpty()
				? Component.literal("Can't read: " + problems.get(0) + (problems.size() > 1 ? " (and " + (problems.size() - 1) + " more)" : "")).withStyle(ChatFormatting.RED)
				: rules > 0 ? Component.literal(rules + (rules == 1 ? " rule" : " rules") + ", all good.").withStyle(ChatFormatting.GREEN)
				: Component.literal("Hover the box to see what a script can do.").withStyle(ChatFormatting.GRAY);
		scriptStatus.setMessage(text);
		scriptStatus.setMaxWidth(panelWidth);
	}

	private static Tooltip tip(Component text) {
		return noTooltips ? null : Tooltip.create(text);
	}

	private List<String> skinChoices() {
		List<String> out = new ArrayList<>(List.of("random", "pack", "default", "folder", "pack,folder", "mineskin", "random,mineskin",
				"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"));
		String now = String.join(",", settings.skins());
		if (!out.contains(now)) out.add(0, now);
		return out;
	}

	/** Where the next setting goes: one or two columns, left to right, top to bottom. */
	private int[] next() {
		int col = row % columns, line = row / columns;
		row++;
		return new int[] {left + col * (buttonWidth + 8), top + line * 24};
	}

	private void onOff(String label, String key, String help) {
		keys.add(key);
		int[] at = next();
		addRenderableWidget(CycleButton.onOffBuilder(Boolean.parseBoolean(settings.value(key)))
				.create(at[0], at[1], buttonWidth, 20, Component.literal(label), (b, v) -> settings.set(key, "" + v)))
				.setTooltip(tip(Component.literal(help)));
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
				.setTooltip(tip(Component.literal(help)));
	}

	@Override
	public void onClose() {
		settings.save();
		Screens.open(minecraft, parent);
	}
}
