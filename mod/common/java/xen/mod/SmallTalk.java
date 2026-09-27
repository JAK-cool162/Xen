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
		if (JOKE.matcher(w).find()) return JOKES[random.nextInt(JOKES.length)];
		if (LAUGH.matcher(w).find() && w.split("\\s+").length <= 2) return c.pick3("Haha.", "Right?", "Hehe.");
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
}
