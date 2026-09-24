package xen.mod.core;

/** Xen's choices every moment (must match xen/actions.py). */
public enum Action {
	IDLE("wait"), FORWARD("walk forward"), BACK("step back"), LEFT("strafe left"), RIGHT("strafe right"),
	JUMP("jump forward"), TURN_LEFT("turn left"), TURN_RIGHT("turn right"), LOOK_UP("look up"),
	LOOK_DOWN("look down"), MINE("mine"), PLACE("place a block"), ATTACK("attack"), EAT("eat");

	public final String verb;

	Action(String verb) {
		this.verb = verb;
	}

	public static final int COUNT = values().length;
}
