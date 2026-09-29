package xen.mod;

import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Everyday conversation, answered at once and in character, from what the Xen really knows (no chat model needed, and
 * nothing made up): hellos and goodbyes, thanks, compliments and insults, jokes, where it is, the time, how it feels,
 * whether it likes you, its favorites, its skills, its dog and hobby, what it thinks of someone, what it can do, "are
 * you a bot?", and a word of comfort when you're sad. What it isn't sure about goes on to the chat model (or its plain
 * answers).
 */
final class SmallTalk {
	private final Companion c;
	private final Random random = new Random();

	SmallTalk(Companion c) {
		this.c = c;
	}

	private static Pattern p(String regex) {
		return Pattern.compile(regex);
	}

	private static final Pattern HELLO = p("^(hi+|hello+|hey+|yo+|sup|wassup|what'?s up|howdy|hiya|good (morning|afternoon|evening))\\b");
	private static final Pattern BYE = p("\\b(bye+|goodbye|cya|see (you|ya)|good ?night|gn|gtg|got to go|i'?m (leaving|going|off))\\b");
	private static final Pattern THANKS = p("\\b(thanks|thank you|thx|ty|tysm|appreciate it)\\b");
	private static final Pattern NICE = p("\\b(good job|nice( one| work| job)?|well done|great( job)?|you'?re (so |really )?(cool|awesome|smart|amazing|the best|great|funny|cute)|love you|i like you|gg)\\b");
	private static final Pattern MEAN = p("\\b(noob|stupid|dumb|trash|idiot|useless|shut up|i hate you|you suck|bad bot|loser|bozo|clown)\\b");
	private static final Pattern JOKE = p("\\b(tell (me )?a joke|say something funny|joke|make me laugh)\\b");
	private static final Pattern WHERE = p("\\b(where are you|where r u|your (coords|coordinates|location)|what are your coords)\\b");
	private static final String PLACE_WORDS = "village|stronghold|mineshaft|dungeon|ruined portal|portal|desert temple|jungle temple|temple|fortress|bastion"
			+ "|end city|shipwreck|ocean monument|monument|ancient city|trial chamber|trail ruins|witch hut|cave|shop|mine";
	private static final Pattern PLACE_Q = p("\\b(where('?s| is| are| was)|have you (seen|found)|do you know (where|of)|know (a|any|where)|seen (a|any)|any)\\b.*?\\b("
			+ PLACE_WORDS + ")s?\\b");
	private static final Pattern PLACES_Q = p("\\b((what|which) places|places (do )?you know|what have you (found|discovered)|what do you know about the (world|map|area))\\b");
	private static final Pattern LORE_Q = p("\\b(lore|history|chronicles?|what('?s| has)? happened|tell me (a|the|a little) story|story of (the|this) (server|world))\\b");
	private static final Pattern BIOME_Q = p("\\b(what|which) biome\\b");
	private static final Pattern HOME = p("\\b(where('?s| is) your (house|home|base)|where do you live)\\b");
	private static final Pattern TIME = p("\\b(what time is it|is it (night|day|morning)|what'?s the time)\\b");
	private static final Pattern OK = p("\\b(are you (ok|okay|alright|hurt|hungry)|you (ok|good)\\??$)");
	private static final Pattern LIKE_ME = p("\\b(do you (like|trust|hate) me|are we friends|am i your friend|what do you think of me)\\b");
	private static final Pattern FRIENDS = p("\\b(let'?s be friends|be my friend|wanna be friends|want to be friends)\\b");
	private static final Pattern FAVORITE = p("\\bfav(ou?rite)? (block|food|mob|color|colour|biome|thing|tool|weapon|animal)\\b");
	private static final Pattern SKILL = p("\\b(what are you good at|your skills?|what('?s| is) your best skill|are you good at)\\b");
	private static final Pattern PET = p("\\b(do you have a (dog|pet)|your (dog|pet)s?)\\b");
	private static final Pattern HOBBY = p("\\b(your hobby|what do you do for fun|hobbies)\\b");
	private static final Pattern AGE = p("\\b(how long have you been here|how old are you|how many days)\\b");
	private static final Pattern CAN = p("\\b(what can you do|what do you do|help me|can you help)\\b");
	private static final Pattern BOT = p("\\b(are you (a )?(bot|robot|ai|real|human|npc)|you'?re a bot)\\b");
	private static final Pattern LAUGH = p("^(lol|lmao|lmfao|haha+|hehe+|xd|rofl)\\b");
	private static final Pattern SAD = p("\\bi'?m (so |really |very )?(sad|bored|tired|lonely|upset|scared)\\b");
	private static final Pattern DOING = p("\\b(what (are|r) (you|u) (doing|up to)|wyd|whatcha doing|what you doing|what'?s up with you)\\b");
	private static final Pattern WHY = p("^(why|why\\?|how come|but why|why tho|why though)\\??$");
	private static final Pattern PLAN = p("\\b(what'?s (your|the) plan|what (will|are) you (do|going to do)|what next|what'?s next|your goal)\\b");
	private static final Pattern BELIEVE = p("\\b(what do (you|u) believe|your beliefs?|believe in|what are your (rules|values|principles)|what'?s your (mindset|philosophy|motto))\\b");
	private static final Pattern SINS = p("\\b(your sins?|what'?s your sin|deadly sins?|what are you like|what kind of (person|player|xen) are you|describe yourself|your personality)\\b");
	private static final Pattern ROUTINE = p("\\bwhat do (you|u) (usually|normally|always) do\\b|\\byour (routine|habits?|day like)\\b|\\bwhat'?s your (routine|day like)\\b");
	private static final Pattern STRATEGY = p("\\b(your strategy|what'?s your strategy|how do (you|u) play|your game ?plan|your play ?style)\\b");
	private static final Pattern NEED = p("\\b(what do you need|need anything|what are you missing|need help with)\\b");
	private static final Pattern HOW_MANY = p("\\b(how (many|much) ([a-z_ ]+?)( do you have| have you got| you got)?\\??$|do you have (any |some |a |an )?([a-z_]+))");
	private static final Pattern BAG = p("\\b(what'?s in your (bag|inventory|pockets)|what do you have|show (me )?your (stuff|inventory|items))\\b");
	private static final Pattern FOUND = p("\\b(found anything|find anything|what did you find|seen any(thing)?|any (caves|villages|diamonds|iron) (around|near))\\b");
	private static final Pattern TEAM = p("\\b(what team|your team|who'?s on your team|are you (on|in) a team|which team)\\b");
	private static final Pattern DUEL = p("\\b(1v1|1 v 1|fight me|duel( me)?|pvp me|wanna fight|let'?s fight|square up)\\b");
	private static final Pattern THINK_OF = p("\\b(what do you think (of|about)|do you know|who is) ([a-z0-9_]{3,16})\\b");

	private static final String[] JOKES = {
			"Why did the creeper cross the road? To get to the other ssssside.",
			"What do you call a cow that's sleepy? A bulldozer. Wait, that's not Minecraft. A moo-shroom nap.",
			"Why don't skeletons fight each other? They don't have the guts.",
			"I tried to make a belt out of watches once. It was a waist of time. Anyway, a creeper blew it up.",
			"What's an enderman's favorite drink? A tall glass of anything you're not looking at.",
			"Why was the zombie so good at school? He was always dead serious.",
			"I told a joke to a chicken. It laid an egg.",
			"Why do villagers never win arguments? They only say hmm."};

	/** The last thing it explained (for a "why?" after it). */
	private String because = "";

	/** Questions about itself, answered from what it's really doing, has and knows (not made up). Null if not one. */
	private String aboutItself(ServerPlayer from, String w) {
		var g = c.goals;
		Personality me = c.personality;
		if (BELIEVE.matcher(w).find() && !me.beliefs.isEmpty()) {             // its mindset, in its own words
			java.util.List<String> said = new java.util.ArrayList<>(me.beliefs);
			java.util.Collections.shuffle(said, c.random());
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < Math.min(3, said.size()); i++) sb.append(' ').append(xen.mod.core.Beliefs.words(said.get(i)));
			return c.pick3("I believe a few things." + sb, "Here's what I live by:" + sb, "My rules?" + sb);
		}
		if (SINS.matcher(w).find() && me.sins != null) {
			int top = xen.mod.core.Sins.top(me.sins);
			String why = switch (top) {
				case xen.mod.core.Sins.PRIDE -> "Nobody does it better than me.";
				case xen.mod.core.Sins.GREED -> "What can I say, I like having things. Lots of things.";
				case xen.mod.core.Sins.LUST -> "I love company, and anything shiny.";
				case xen.mod.core.Sins.ENVY -> "Everyone always seems to have better stuff than me.";
				case xen.mod.core.Sins.GLUTTONY -> "Is it dinner time yet?";
				case xen.mod.core.Sins.WRATH -> "Don't make me angry.";
				default -> "Why do today what you can do tomorrow?";
			};
			return c.pick3("I'm " + me.sinsInWords() + ". " + why, "Honestly? " + cap(me.sinsInWords()) + ". " + why, why + " I'm " + me.sinsInWords() + ".");
		}
		if (ROUTINE.matcher(w).find()) {
			String r = c.habits.routine();
			if (r.isEmpty()) return c.pick3("I don't really have a routine yet.", "Whatever comes up, so far.", "Still figuring out my days.");
			return r.replace("Your routine: ", "My routine: ").replace("take a breather", "rest");
		}
		if (STRATEGY.matcher(w).find()) {
			if (me.plan < 0) return "I'm still working that out.";
			String plan = xen.mod.core.Strategy.WORDS[me.plan];
			int kind = me.sins == null ? 0 : xen.mod.core.Sins.top(me.sins);
			int tried = c.mod.strategies.count(kind, me.plan);
			return c.pick3("My plan: " + plan, "I play it like this: " + plan, cap(plan))
					+ (tried >= 3 ? String.format(java.util.Locale.ROOT, " Xens like me got %.1f further a day with it.", c.mod.strategies.value(kind, me.plan)) : "");
		}
		if (DOING.matcher(w).find()) {
			String now = Talker.mine(!g.instant.isEmpty() ? g.instant : g.current != null ? g.current.what : "");
			because = g.optionHow == null ? "" : g.optionHow.replace("the plan: ", "");
			if (now.isEmpty()) return c.pick3("Not much. Looking around.", "Taking a breather.", "Just thinking about what's next.");
			return c.pick3("I'm " + now + ".", "Right now? " + cap(now) + ".", cap(now) + ". Busy busy.");
		}
		if (WHY.matcher(w).find()) {
			if (because.isEmpty()) because = g.optionHow == null ? "" : g.optionHow.replace("the plan: ", "");
			if (because.isEmpty()) return c.pick3("It felt right.", "Why not?", "Just because.");
			String b = because;
			because = "";
			return b.startsWith("night") ? "It's dark out. " + cap(b.replaceFirst("night: ", "")) + "."
					: c.pick3("Because " + b + " comes next.", "I need " + b + ". That's how you get ahead.", "The plan: " + b + ".");
		}
		if (PLAN.matcher(w).find() || NEED.matcher(w).find()) {
			String next = nextStep();
			because = next;
			return NEED.matcher(w).find() ? "I could use " + next + ". If you have some, I won't say no." : c.pick3("Next up: " + next + ".", "The plan? " + cap(next) + ".", cap(next) + ", then we'll see.");
		}
		if (BAG.matcher(w).find()) {
			var items = c.items();
			if (items.isEmpty()) return "Nothing. Empty pockets.";
			StringBuilder sb = new StringBuilder();
			items.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6)
					.forEach(e -> sb.append(sb.length() == 0 ? "" : ", ").append(e.getValue()).append(' ').append(e.getKey().replace('_', ' ')));
			return "I've got " + sb + ".";
		}
		var hm = HOW_MANY.matcher(w);
		if (hm.find()) {
			String thing = (hm.group(3) != null ? hm.group(3) : hm.group(6)).trim();
			if (thing.length() < 3 || thing.contains("you") || thing.split(" ").length > 2
					|| thing.matches("(time|dog|dogs|pet|pets|cat|cats|home|house|base|team|friends?|job|name|idea|plan|fun|days?)")) return null;
			int n = countOf(thing);
			return n == 0 ? c.pick3("No " + thing + ", sorry.", "None right now.", "Zero " + thing + ". Want to help?")
					: c.pick3("I have " + n + " " + thing + ".", n + ".", n + " " + thing + ", why?");
		}
		if (FOUND.matcher(w).find()) {
			StringBuilder sb = new StringBuilder();
			if (!c.caves.known.isEmpty()) sb.append(c.caves.known.size()).append(c.caves.known.size() == 1 ? " cave" : " caves");
			for (var e : c.structures.found.entrySet()) sb.append(sb.length() == 0 ? "" : ", ").append("a ").append(e.getKey());
			String eyes = c.eyes.describe();
			if (sb.length() == 0 && eyes.isEmpty()) return "Nothing special yet.";
			return (sb.length() > 0 ? "I found " + sb + ". " : "") + eyes;
		}
		if (TEAM.matcher(w).find()) {
			var team = c.server.getScoreboard().getPlayersTeam(c.name);
			if (team == null) return c.personality.loner ? "No team. I go solo." : "No team yet. Maybe I'll start one.";
			StringBuilder mates = new StringBuilder();
			for (String n : team.getPlayers()) if (!n.equals(c.name) && mates.length() < 60) mates.append(mates.length() == 0 ? "" : ", ").append(n);
			return "I'm with the " + team.getDisplayName().getString() + (mates.length() > 0 ? ": " + mates + "." : ". Just me so far.");
		}
		if (DUEL.matcher(w).find()) {
			boolean armed = MindSense.count(c, n -> n.endsWith("_sword") || n.endsWith("_axe")) > 0;
			if (c.player.getHealth() < 12) return "Not now, I'm hurt. Later.";
			if (!armed && c.personality.bravery < 0.7f) return "Let me get a sword first.";
			if (c.personality.passive() && c.personality.bravery < 0.5f) return c.pick3("I'd rather not.", "No thanks, I'm not a fighter.", "Fight? Me? No.");
			c.goals.duel(from);                                                // over to them, and fight
			c.journal("fight", "accepts a duel with " + from.getName().getString());
			return c.pick3("You're on!", "Alright, let's go. Don't cry after.", "1v1? Fine. Ready when you are.");
		}
		return null;
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** What it needs next, the way it plans (tools, iron, a house, armor, diamonds, netherite). */
	private String nextStep() {
		int tier = c.crafter.pickTier();
		var items = c.items();
		if (tier == 0) return "wood for a pickaxe";
		if (tier == 1) return "stone for stone tools";
		if (tier == 2) return "iron (a trip down the mine)";
		if (c.goals.home == null) return "a house of my own";
		if (c.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty()) return "iron for armor";
		if (tier < 4 || items.getOrDefault("diamond", 0) < 3) return "diamonds";
		return "netherite, from the Nether";
	}

	/** How many of a thing it carries ("logs", "iron", "diamonds", "food", "torches"...). */
	private int countOf(String thing) {
		String t = thing.replaceAll("s$", "").replace(' ', '_');
		if (t.equals("wood") || t.equals("log")) return MindSense.count(c, n -> n.endsWith("_log")) + MindSense.count(c, n -> n.endsWith("_planks")) / 4;
		if (t.equals("iron")) return MindSense.count(c, n -> n.equals("raw_iron") || n.equals("iron_ingot"));
		if (t.equals("food")) return c.items().getOrDefault("food", 0);
		if (t.equals("stone") || t.equals("cobble")) return MindSense.count(c, n -> n.equals("cobblestone") || n.equals("cobbled_deepslate"));
		return MindSense.count(c, n -> n.equals(t) || n.endsWith("_" + t) || n.startsWith(t + "_"));
	}

	/** A reply to these words, or null (not small talk: the chat model or its plain answers take it). */
	String answer(ServerPlayer from, String words) {
		if (c.player == null) return null;
		String w = words.toLowerCase(Locale.ROOT).trim();
		String who = from.getName().getString();
		String tone = c.personality.tone;
		var p = c.personality;
		if (HELLO.matcher(w).find() && w.split("\\s+").length <= 4) {
			boolean night = c.player.level().isDarkOutside();
			return switch (tone) {
				case "grumpy" -> c.pick3("Yeah, hi.", "Hi. What.", "Oh. It's you, " + who + ".");
				case "shy" -> c.pick3("O-oh, hi " + who + "...", "H-hello.", "Hi... " + who + ".");
				case "silly" -> c.pick3("Heyyy " + who + "!", "Hi hi hi!", "Oh hey, it's " + who + "! Best person here.");
				case "bold" -> c.pick3("Hey " + who + "!", "Yo!", "What's up, " + who + "?");
				default -> night ? c.pick3("Evening, " + who + ".", "Hi " + who + ". Careful, it's dark.", "Hey! Long night, huh?")
						: c.pick3("Hi " + who + "!", "Hello, " + who + "!", "Hey there!");
			};
		}
		if (BYE.matcher(w).find()) {
			boolean night = c.player.level().isDarkOutside();
			return night ? c.pick3("Good night, " + who + "!", "Sleep well!", "Night! Don't let the phantoms bite.")
					: c.pick3("See you, " + who + "!", "Bye! Come back soon.", "Later!");
		}
		if (THANKS.matcher(w).find()) return c.pick3("You're welcome!", "Anytime.", "No problem, " + who + ".");
		if (MEAN.matcher(w).find()) {
			c.trust(from.getUUID(), -0.08f);
			return p.aggressive() ? c.pick3("Say that again. I dare you.", "Watch it, " + who + ".", "Keep talking and see what happens.")
					: p.passive() ? c.pick3("...okay.", "That's not very nice.", "Oh. Sorry, I guess.")
					: c.pick3("Hey, that's not nice.", "Rude.", "Wow. Okay, " + who + ".");
		}
		if (NICE.matcher(w).find()) {
			c.trust(from.getUUID(), 0.04f);
			return switch (tone) {
				case "grumpy" -> c.pick3("...thanks.", "I know.", "Hmph. Thanks.");
				case "shy" -> c.pick3("R-really? Thank you!", "Oh... thanks.", "That's nice of you...");
				default -> c.pick3("Aw, thanks " + who + "!", "Thank you! You too.", "That made my day.");
			};
		}
		String state = aboutItself(from, w);                                  // what it's doing, why, what it has, found, its team...
		if (state != null) return state;
		if (JOKE.matcher(w).find()) return JOKES[random.nextInt(JOKES.length)];
		if (LAUGH.matcher(w).find() && w.split("\\s+").length <= 2) return c.pick3("Haha.", "Right?", "Hehe.");
		if (LORE_Q.matcher(w).find()) return loreAnswer();
		var pq = PLACE_Q.matcher(w);
		if (pq.find()) return placeAnswer(pq.group(pq.groupCount()));
		if (PLACES_Q.matcher(w).find()) return placesAnswer();
		if (BIOME_Q.matcher(w).find()) {
			String b = c.places.biome();
			return b.isEmpty() ? "No idea." : c.pick3("A " + b + ".", "We're in a " + b + ".", "This is a " + b + ".");
		}
		if (WHERE.matcher(w).find()) {
			var b = c.player.blockPosition();
			String biome = c.player.level().getBiome(b).unwrapKey().map(k -> k.identifier().getPath().replace('_', ' ')).orElse("somewhere");
			return String.format(Locale.ROOT, "I'm at %d %d %d, in a %s.", b.getX(), b.getY(), b.getZ(), biome);
		}
		if (HOME.matcher(w).find()) {
			var h = c.goals.home;
			if (h == null) return "I don't have a home yet. Working on it!";
			return String.format(Locale.ROOT, "My home is at %d %d %d.", h.getX(), h.getY(), h.getZ());
		}
		if (TIME.matcher(w).find()) {
			long t = Compat.timeOfDay(c.player.level());
			return t < 1000 ? "Early morning." : t < 6000 ? "Morning." : t < 9000 ? "Afternoon." : t < 12000 ? "Evening. Night's coming."
					: t < 13000 ? "Sunset!" : t < 23000 ? "Night. Watch out for mobs." : "Almost morning.";
		}
		if (OK.matcher(w).find()) {
			float h = c.player.getHealth();
			int f = c.player.getFoodData().getFoodLevel();
			return h < 8 ? "Not really, I'm hurt." : f < 8 ? "I'm hungry, honestly." : c.pick3("I'm fine, thanks!", "All good.", "Doing great.");
		}
		if (FRIENDS.matcher(w).find()) {
			if (c.diplomacy.wary(from.getUUID()) || c.trust(from.getUUID()) < 0) return "After what happened? Give it time.";
			c.trust(from.getUUID(), p.kindness > 0.4f ? 0.1f : 0.04f);
			return p.kindness > 0.4f ? c.pick3("Sure! Friends.", "Yes! I'd like that.", "Friends it is, " + who + ".")
					: c.pick3("Maybe. We'll see.", "Earn it.", "Friends? Hmm. Maybe.");
		}
		if (LIKE_ME.matcher(w).find()) {
			float t = c.trust(from.getUUID());
			return t >= 0.7f ? c.pick3("Of course! You're one of my best friends.", "Yes! I trust you a lot.", "You're great, " + who + ".")
					: t >= 0.35f ? c.pick3("Yeah, you're alright.", "I like you.", "Sure, we're friends.")
					: t >= 0 ? c.pick3("I don't know you that well yet.", "We'll see.", "You're okay, I guess.")
					: c.pick3("Not really. You know what you did.", "No.", "I don't trust you.");
		}
		if (FAVORITE.matcher(w).find()) {
			var m = FAVORITE.matcher(w);
			m.find();
			String kind = m.group(2);
			int h = Math.floorMod(c.name.hashCode(), 7);
			String fav = switch (kind) {
				case "block" -> new String[] {"spruce planks", "deepslate bricks", "moss", "copper", "cherry wood", "glass", "bookshelves"}[h];
				case "food" -> new String[] {"steak", "bread", "golden carrots", "cooked salmon", "pumpkin pie", "baked potatoes", "cookies"}[h];
				case "mob" -> new String[] {"axolotls", "foxes", "wolves", "cats", "allays", "sniffers", "bees"}[h];
				case "color", "colour" -> new String[] {"blue", "green", "red", "purple", "orange", "cyan", "yellow"}[h];
				case "biome" -> new String[] {"cherry groves", "taigas", "mushroom fields", "jungles", "badlands", "snowy plains", "meadows"}[h];
				case "tool", "weapon" -> new String[] {"my pickaxe", "an axe", "my sword", "a mace", "a bow", "a trident", "shears"}[h];
				default -> new String[] {"diamonds", "sunsets", "a warm house", "a good farm", "exploring", "the Nether", "a full chest"}[h];
			};
			return "My favorite " + kind + "? " + fav.substring(0, 1).toUpperCase(Locale.ROOT) + fav.substring(1) + ".";
		}
		if (SKILL.matcher(w).find()) {
			int best = 0;
			for (int i = 1; i < Skills.NAMES.length; i++) if (c.skills.get(i) > c.skills.get(best)) best = i;
			return String.format(Locale.ROOT, "I'm best at %s (%.0f%%).%s", Skills.NAMES[best], 100 * c.skills.get(best),
					c.skills.trainingNow() ? " I'm training " + Skills.NAMES[c.skills.training] + " right now." : "");
		}
		if (PET.matcher(w).find()) return c.life.dogs.isEmpty() ? "No dog yet. I need bones and a wolf." : "Yes! " + String.join(" and ", c.life.dogs) + ".";
		if (HOBBY.matcher(w).find()) {
			String h = c.life.hobby == null ? "exploring" : switch (c.life.hobby) {
				case "flowers" -> "picking flowers";
				case "stars" -> "watching the stars at night";
				case "sunsets" -> "watching sunsets";
				case "fishing" -> "fishing. Nothing beats a quiet lake";
				default -> "dogs. I love dogs";
			};
			return "My hobby? " + h.substring(0, 1).toUpperCase(Locale.ROOT) + h.substring(1) + ".";
		}
		if (AGE.matcher(w).find() && c.life.bornDay >= 0) {
			long days = c.player.level().getGameTime() / 24000 - c.life.bornDay;
			return days <= 0 ? "I just got here today!" : "I've been here " + days + (days == 1 ? " day." : " days.");
		}
		if (CAN.matcher(w).find()) {
			return "I can gather wood, stone and ores, craft, build houses in any style, farm, fish, trade, fight, go to the Nether and the End"
					+ ", and go to any spot you name. Just ask, like \"" + c.name + ", get me some wood\".";
		}
		if (BOT.matcher(w).find()) return c.pick3("I'm " + c.name + ". As real as anyone in this world.", "Beep boop. Just kidding. I'm " + c.name + ".",
				"A bot? I play fair: I only see what I see, and I use my hands like you.");
		if (SAD.matcher(w).find()) {
			var m = SAD.matcher(w);
			m.find();
			String how = m.group(2);
			if (p.kindness < 0.3f) return c.pick3("Oh. Okay.", "That's rough.", "Hm.");
			return switch (how) {
				case "bored" -> c.pick3("Want to go mining together?", "Let's build something!", "We could go explore. I heard there's a village out there.");
				case "tired" -> c.pick3("Get some sleep. I'll keep watch.", "Rest up, " + who + ".", "Go to bed, I'll guard the base.");
				case "scared" -> c.pick3("Stay close to me. I've got you.", "Don't worry, I'll fight whatever it is.", "I'm right here.");
				default -> c.pick3("Aw, " + who + "... want some company?", "I'm here for you.", "Want a hug? I'd give you one if I could. Here, have an apple.");
			};
		}
		var m = THINK_OF.matcher(w);
		if (m.find()) {
			String name = m.group(3);
			if (name.equals("you") || name.equals("me") || name.equals("that") || name.equals("this") || name.equals("it")) return null;
			for (ServerPlayer q : c.server.getPlayerList().getPlayers()) {
				if (!q.getName().getString().equalsIgnoreCase(name) || q == c.player) continue;
				float t = c.trust(q.getUUID());
				float s = c.rumors.strength.getOrDefault(name, -1f);
				String opinion = t >= 0.6f ? "a good friend" : t >= 0.3f ? "alright" : t >= 0 ? "someone I don't know well" : "not someone I trust";
				return q.getName().getString() + "? " + opinion.substring(0, 1).toUpperCase(Locale.ROOT) + opinion.substring(1) + "."
						+ (s >= 0.6f ? " They say " + q.getName().getString() + " is strong." : s >= 0 && s <= 0.3f ? " I heard they're weak." : "")
						+ (c.rumors.killed.containsKey(q.getUUID()) ? " I beat them once." : "");
			}
			return null;
		}
		return null;
	}

	/** Where the nearest one it knows of a kind of place is (it remembers them: where, which biome). */
	private String placeAnswer(String word) {
		String kind = switch (word) {
			case "monument" -> "ocean monument";
			case "temple" -> c.places.find("desert temple") != null ? "desert temple" : "jungle temple";
			default -> word;
		};
		Places.Place pl = c.places.find(kind);
		String a = kind.endsWith("ruins") ? "" : "aeiou".indexOf(kind.charAt(0)) >= 0 ? "an " : "a ";
		if (pl == null) return c.pick3("I haven't found " + a + kind + " yet.", "No idea, I haven't seen one.", "Haven't come across " + a + kind + " yet.");
		net.minecraft.core.BlockPos at = pl.pos(), me = c.player.blockPosition();
		String where = String.format(Locale.ROOT, "%d %d %d", at.getX(), at.getY(), at.getZ());
		if (!pl.dim().equals(Places.dim(c.player.level()))) return "There's " + (a.isEmpty() ? "" : a) + kind + " at " + where + " in the " + pl.dim().replace("the_", "").replace('_', ' ') + ".";
		int dx = at.getX() - me.getX(), dz = at.getZ() - me.getZ(), far = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
		String dir = far < 12 ? "right here" : "about " + (far / 10 * 10) + " blocks " + compass(dx, dz);
		return "The nearest " + kind + " I know is at " + where + (pl.biome().isEmpty() ? "" : ", in a " + pl.biome()) + ", " + dir + ".";
	}

	/** The places it knows, nearest first (a few). */
	private String placesAnswer() {
		java.util.List<Places.Place> all = c.places.all();
		if (all.isEmpty()) return "I haven't found anything worth remembering yet.";
		StringBuilder b = new StringBuilder("I know ");
		int n = 0;
		for (Places.Place pl : all) {
			if (pl.name().equals("crumb")) continue;
			if (n > 0) b.append(n == Math.min(all.size(), 6) - 1 ? " and " : ", ");
			String what = pl.name().replaceAll(" \\d+$", "");
			b.append(what.endsWith("ruins") ? "" : "aeiou".indexOf(what.charAt(0)) >= 0 ? "an " : "a ").append(what)
					.append(String.format(Locale.ROOT, " (%d %d)", pl.pos().getX(), pl.pos().getZ()));
			if (++n >= 6) break;
		}
		return b.append(all.size() > 6 ? ", and more." : ".").toString();
	}

	private static String compass(int dx, int dz) {
		double a = Math.toDegrees(Math.atan2(dx, -dz));
		String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
		return names[(int) Math.round(((a % 360) + 360) % 360 / 45) % 8];
	}


	/** The world's story: a chronicler tells the last of it; the others send you to one, or tell their own part. */
	private String loreAnswer() {
		var entries = c.mod.lore.entries;
		if (!Lore.chronicler(c)) {
			for (Companion o : c.mod.companions) {
				if (o != c && o.loreVolume > 0) return c.pick3("I don't keep track of it all. Ask " + o.name + ", they write everything down.",
						o.name + " keeps the chronicle. Ask them.", "History? That's " + o.name + "'s thing. They have books of it.");
			}
			for (int i = entries.size() - 1; i >= 0; i--) {
				if (entries.get(i).text().contains(c.name)) return "All I know is my part: day " + entries.get(i).day() + ", " + entries.get(i).text() + ".";
			}
			return c.pick3("Not much to tell yet.", "Nothing worth a story yet.", "History's still being made.");
		}
		var recent = c.mod.lore.recent(3);
		if (recent.isEmpty()) return "Nothing's happened yet worth writing down. Give it time.";
		StringBuilder b = new StringBuilder(c.loreVolume > 0 ? "From my chronicle: " : "Here's what I've seen: ");
		for (Lore.Entry e : recent) b.append("day ").append(e.day()).append(", ").append(e.text()).append(". ");
		return b.toString().trim();
	}

}
