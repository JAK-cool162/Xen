package xen.mod;

import xen.mod.core.Mind;
import xen.mod.core.Sins;

import java.util.ArrayList;
import java.util.List;

/**
 * On a team, what it does for the team, like players on an SMP: some keep the base (farms, chests, walls), some go
 * mining, some hunt the monsters around the base and down in the caves, some scout. Nobody hands the roles out: each
 * Xen knows what's possible and picks for itself, from its nature and what its team is short of, says so to its
 * team, and thinks again after a game day (or when the team changes). The role only leans its choices: its mind
 * still decides what it does.
 */
final class TeamRole {
	enum Role {
		KEEPER("keep the base: the farms, the chests, the walls"), MINER("go mining for the ore"),
		FIGHTER("hunt the monsters round the base and in the caves"), EXPLORER("scout for caves, villages and structures");

		final String does;

		Role(String does) {
			this.does = does;
		}
	}

	private final Companion c;
	Role role;
	private long pickedAt = -100000;
	private int mateCount;

	TeamRole(Companion c) {
		this.c = c;
	}

	/** Its teammates: the other Xens on its team, in its world. */
	List<Companion> mates() {
		List<Companion> out = new ArrayList<>();
		var team = c.player == null ? null : c.player.getTeam();
		if (team == null) return out;
		for (Companion o : c.mod.companions) {
			if (o == c || o.player == null || !o.player.isAlive() || o.player.level() != c.player.level() || o.minion) continue;
			if (o.player.getTeam() == team) out.add(o);
		}
		return out;
	}

	/** Once a minute: on a team, a role (kept for a game day, unless the team changes); off one, none. */
	void tick() {
		if (c.player == null || c.inArena || c.minion || c.player.tickCount % 1200 != 600) return;
		List<Companion> mates = mates();
		if (mates.isEmpty() || c.personality.loner) {
			role = null;
			return;
		}
		long now = c.player.level().getGameTime();
		if (role != null && now - pickedAt < 24000 && mates.size() == mateCount) return;
		mateCount = mates.size();
		Role was = role;
		role = pick(mates);
		pickedAt = now;
		if (role == was) return;
		c.journal("thinks", "on a team of " + (mates.size() + 1) + ": it'll " + role.does + " (" + why(mates) + ")");
		c.chatter(c.pick3("I'll " + role.does + ".", "Team, I've got this: I'll " + role.does + ".", "My job: " + role.does + "."), false);
	}

	/** What it's like and what the team is short of, weighed (a little chance in it, like a person deciding). */
	private Role pick(List<Companion> mates) {
		var p = c.personality;
		var k = c.skills;
		float[] s = new float[Role.values().length];
		s[Role.KEEPER.ordinal()] = 0.3f + 0.5f * p.diligence + 0.3f * p.kindness + 0.2f * p.loyalty - 0.3f * p.curiosity + 0.3f * (k.get(Skills.FARM) - 0.5f);
		s[Role.MINER.ordinal()] = 0.4f + 0.4f * p.money + 0.3f * p.sin(Sins.GREED) + 0.4f * (k.get(Skills.MINE) - 0.5f);
		s[Role.FIGHTER.ordinal()] = 0.2f + 0.6f * p.bravery + 0.4f * p.sin(Sins.WRATH) + 0.4f * (k.get(Skills.FIGHT) - 0.5f) - (c.hands.hasWeapon() ? 0 : 0.3f);
		s[Role.EXPLORER.ordinal()] = 0.2f + 0.7f * p.curiosity - 0.2f * p.diligence;
		for (Companion m : mates) if (m.teamRole.role != null) s[m.teamRole.role.ordinal()] -= 0.35f;   // (taken already: the team needs the others more)
		boolean home = c.goals.home != null || mates.stream().anyMatch(m -> m.goals.home != null);
		if (home && c.places.get("farm") == null) s[Role.KEEPER.ordinal()] += 0.25f;                     // a base and no farm yet
		if (c.crafter.pickTier() >= 3) s[Role.MINER.ordinal()] += 0.2f;                                   // (an iron pickaxe: diamonds are possible)
		Role best = Role.MINER;
		float top = -9;
		for (Role r : Role.values()) {
			float v = s[r.ordinal()] + 0.15f * c.random().nextFloat();
			if (v > top) {
				top = v;
				best = r;
			}
		}
		return best;
	}

	private String why(List<Companion> mates) {
		List<String> taken = new ArrayList<>();
		for (Companion m : mates) if (m.teamRole.role != null) taken.add(m.name + " " + m.teamRole.role.name().toLowerCase(java.util.Locale.ROOT));
		return taken.isEmpty() ? "its own pick" : "the others: " + String.join(", ", taken);
	}

	/** What its role adds to its choices (its mind still decides). */
	void lean(float[] b) {
		if (role == null) return;
		switch (role) {
			case KEEPER -> {
				b[Mind.FARM] += 0.5f;
				b[Mind.STORE] += 0.4f;
				b[Mind.HOUSE] += 0.3f;
				b[Mind.CRAFT] += 0.2f;
				b[Mind.EXPLORE] -= 0.3f;
			}
			case MINER -> {
				b[Mind.MINE] += 0.5f;
				b[Mind.SMELT] += 0.1f;
			}
			case FIGHTER -> {
				b[Mind.GUARD] += 0.5f;
				b[Mind.FIGHT] += 0.3f;
				b[Mind.MINE] += 0.1f;                                                   // (the caves: where the monsters are)
			}
			case EXPLORER -> b[Mind.EXPLORE] += 0.5f;
		}
	}
}
