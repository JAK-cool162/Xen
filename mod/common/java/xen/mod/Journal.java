package xen.mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The journal (an experiment, for making Xens better): what each Xen sees, thinks, says and hears, how it finds its
 * way and what the solver tries when it's stuck, with the time. The last few thousand lines are kept; the settings
 * screen (Experimental) copies them or saves them to config/xen/logs/, and so does /xen log.
 */
public final class Journal {
	private static final int KEEP = 5000;
	private final Deque<String> lines = new ArrayDeque<>();
	/** Each death's last moments (what that Xen saw, thought and did just before), kept apart so they outlast the rest. */
	private final Deque<String> deaths = new ArrayDeque<>();
	private final Path dir;

	Journal(Path dir) {
		this.dir = dir;
	}

	synchronized void add(String who, String kind, String text) {
		String line = String.format("%s %-8s %-7s %s", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), who, kind, text);
		lines.addLast(line);
		while (lines.size() > KEEP) lines.removeFirst();
	}

	/** A Xen died: its last few dozen lines are kept with the death (the journal itself rolls over fast). */
	synchronized void died(String who, String how) {
		Deque<String> last = new ArrayDeque<>();
		String tag = String.format(" %-8s ", who);
		var it = lines.descendingIterator();
		while (it.hasNext() && last.size() < 40) {
			String l = it.next();
			if (l.startsWith(tag, 8)) last.addFirst(l);
		}
		StringBuilder sb = new StringBuilder(who + " died (" + how + "), " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + ":\n");
		for (String l : last) sb.append("  ").append(l).append('\n');
		deaths.addLast(sb.toString());
		while (deaths.size() > 30) deaths.removeFirst();
	}

	/** All of it, oldest first (and what the solver has learned so far, at the end). */
	public synchronized String text() {
		StringBuilder sb = new StringBuilder("Xen journal (" + lines.size() + " lines): time, Xen, what, and the words\n\n");
		for (String l : lines) sb.append(l).append('\n');
		if (!deaths.isEmpty()) {
			sb.append("\nThe last moments before each death (the latest ").append(deaths.size()).append("):\n\n");
			for (String d : deaths) sb.append(d).append('\n');
		}
		if (XenMod.INSTANCE != null) sb.append("\nWhat the solver has learned (place, way out: how often it worked):\n").append(XenMod.INSTANCE.solverMind.report());
		return sb.toString();
	}

	/** Saved as a file; where it went. */
	public Path save() throws IOException {
		Files.createDirectories(dir);
		Path file = dir.resolve("journal-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".txt");
		Files.writeString(file, text(), StandardCharsets.UTF_8);
		return file;
	}

	synchronized int size() {
		return lines.size();
	}
}
