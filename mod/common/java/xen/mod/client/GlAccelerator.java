package xen.mod.client;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import xen.mod.talk.Llm;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The chat model's matrix products on the graphics card, with plain OpenGL 3.3 shaders: the weights live in textures,
 * and a fragment shader works out one number of the product per pixel. It has its own small hidden window and
 * OpenGL context, so it doesn't touch the game's rendering at all: it works the same with vanilla, Sodium, Iris or a
 * Vulkan renderer, as long as the graphics driver offers OpenGL 3.3 (almost every PC; Macs too). The window comes from
 * GLFW (Minecraft up to 26.2) or SDL (26.3), whichever the game uses. All OpenGL work happens on one thread of its own.
 */
public final class GlAccelerator implements Llm.Accelerator {
	/** Texture width it packs matrix rows into (every GPU with OpenGL 3.3 takes 4096; most take 16384). */
	private static final int WIDTH = 4096;
	private static final String VERTEX = """
			#version 330 core
			void main() {
				vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
				gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
			}
			""";
	/** One output number per pixel: a row of int8 weights (4 per texel) times the input, block by block with its scale. */
	private static final String FRAGMENT = """
			#version 330 core
			uniform isampler2D W;
			uniform sampler2D S;
			uniform sampler2D X;
			uniform int cols4, blocks, perLine, rows, n, outW;
			out vec4 result;
			void main() {
				int idx = int(gl_FragCoord.y) * outW + int(gl_FragCoord.x);
				if (idx >= n * rows) { result = vec4(0.0); return; }
				int t = idx / rows;
				int r = idx - t * rows;
				int line = r / perLine;
				int slot = r - line * perLine;
				int wx = slot * cols4, sx = slot * blocks;
				float sum = 0.0;
				for (int b = 0; b < blocks; b++) {
					float part = 0.0;
					for (int j = 0; j < 8; j++) {
						int i = b * 8 + j;
						part += dot(vec4(texelFetch(W, ivec2(wx + i, line), 0)), texelFetch(X, ivec2(i, t), 0));
					}
					sum += part * texelFetch(S, ivec2(sx + b, line), 0).r;
				}
				result = vec4(sum, 0.0, 0.0, 1.0);
			}
			""";

	/** A matrix on the GPU. */
	private record Matrix(int weights, int scales, int rows, int cols, int perLine) {}

	private final ExecutorService gl = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "xen-gpu");
		t.setDaemon(true);
		return t;
	});
	private final Window window;
	private final List<Matrix> matrices = new ArrayList<>();
	private String name = "?";
	private int program, vao, fbo, xTex, outTex, xW, xH, outW, outH, maxSize;
	private int uCols4, uBlocks, uPerLine, uRows, uN, uOutW;
	private FloatBuffer xBuf, outBuf;
	private long bytes;

	private GlAccelerator(Window window) {
		this.window = window;
	}

	/**
	 * A GPU for the chat model, or null when there isn't a usable one: no OpenGL 3.3, or (unless forced) only a
	 * software renderer, which would be slower than the CPU path.
	 */
	public static Llm.Accelerator create(boolean force, Consumer<String> log) {
		Window w = null;
		GlAccelerator a = null;
		try {
			w = onMainThread(Window::open);
			if (w == null) {
				log.accept("No window for the GPU (" + Window.problem + "), so the chat model stays on the CPU.");
				return null;
			}
			a = new GlAccelerator(w);
			GlAccelerator self = a;
			String problem = a.run(() -> self.setUp(force));
			if (problem != null) {
				log.accept("Not using the GPU for the chat model: " + problem + ".");
				a.close();
				return null;
			}
			return a;
		} catch (Throwable e) {
			log.accept("Not using the GPU for the chat model: " + e);
			if (a != null) a.close();
			else if (w != null) {
				Window ww = w;
				onMainThread(() -> {
					ww.destroy();
					return null;
				});
			}
			return null;
		}
	}

	/** Run on the game's main thread (windows have to be made there) and wait. */
	private static <T> T onMainThread(java.util.concurrent.Callable<T> job) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) {
			try {
				return job.call();
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
		CompletableFuture<T> f = new CompletableFuture<>();
		mc.execute(() -> {
			try {
				f.complete(job.call());
			} catch (Throwable e) {
				f.completeExceptionally(e);
			}
		});
		try {
			return f.get(20, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private <T> T run(java.util.concurrent.Callable<T> job) throws Exception {
		try {
			return gl.submit(job).get(120, TimeUnit.SECONDS);
		} catch (java.util.concurrent.ExecutionException e) {
			throw e.getCause() instanceof Exception x ? x : e;
		}
	}

	private static String gpuName;

	/**
	 * The game's graphics card, as its driver names it ("Adreno (TM) 740", "NVIDIA GeForce RTX 3060/PCIe/SSE2"), for the
	 * settings screen. Asked once, on the render thread; "unknown" if the game doesn't draw with OpenGL.
	 */
	static String gpuName() {
		if (gpuName != null) return gpuName;
		try {
			String r = GL11.glGetString(GL11.GL_RENDERER), v = GL11.glGetString(GL11.GL_VENDOR);
			gpuName = r == null ? "unknown" : r + (v != null && !r.toLowerCase(Locale.ROOT).contains(v.toLowerCase(Locale.ROOT).split(" ")[0]) ? " (" + v + ")" : "");
		} catch (Throwable e) {
			gpuName = "unknown";
		}
		return gpuName;
	}

	// ----------------------------------------------------------------------- setting up
	/** On the GL thread: the context, a check of what it is, the shader. A reason it can't be used, or null. */
	private String setUp(boolean force) {
		window.makeCurrent();
		GL.createCapabilities();
		String version = GL11.glGetString(GL11.GL_VERSION), renderer = GL11.glGetString(GL11.GL_RENDERER);
		String vendor = GL11.glGetString(GL11.GL_VENDOR);
		name = renderer + " (OpenGL " + version + ")";
		int[] v = parseVersion(version);
		if (v[0] < 3 || v[0] == 3 && v[1] < 3) return "the graphics driver has OpenGL " + version + " (3.3 needed)";
		String r = (renderer + " " + vendor).toLowerCase(Locale.ROOT);
		boolean software = r.contains("llvmpipe") || r.contains("softpipe") || r.contains("swiftshader") || r.contains("software")
				|| r.contains("microsoft basic") || r.contains("gdi generic");
		if (software && !force) return "there's no graphics card, only a software renderer (" + renderer + "), which is slower than the CPU path";
		maxSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
		if (maxSize < WIDTH) return "textures are limited to " + maxSize + " pixels";
		program = link(compile(GL20.GL_VERTEX_SHADER, VERTEX), compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT));
		GL20.glUseProgram(program);
		GL20.glUniform1i(GL20.glGetUniformLocation(program, "W"), 0);
		GL20.glUniform1i(GL20.glGetUniformLocation(program, "S"), 1);
		GL20.glUniform1i(GL20.glGetUniformLocation(program, "X"), 2);
		uCols4 = GL20.glGetUniformLocation(program, "cols4");
		uBlocks = GL20.glGetUniformLocation(program, "blocks");
		uPerLine = GL20.glGetUniformLocation(program, "perLine");
		uRows = GL20.glGetUniformLocation(program, "rows");
		uN = GL20.glGetUniformLocation(program, "n");
		uOutW = GL20.glGetUniformLocation(program, "outW");
		vao = GL30.glGenVertexArrays();
		fbo = GL30.glGenFramebuffers();
		GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
		GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
		GL11.glDisable(GL11.GL_BLEND);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		return null;
	}

	private static int[] parseVersion(String version) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)").matcher(version == null ? "" : version);
		return m.find() ? new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : new int[] {0, 0};
	}

	private static int compile(int type, String source) {
		int s = GL20.glCreateShader(type);
		GL20.glShaderSource(s, source);
		GL20.glCompileShader(s);
		if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException("shader: " + GL20.glGetShaderInfoLog(s));
		return s;
	}

	private static int link(int vs, int fs) {
		int p = GL20.glCreateProgram();
		GL20.glAttachShader(p, vs);
		GL20.glAttachShader(p, fs);
		GL20.glLinkProgram(p);
		if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException("shader: " + GL20.glGetProgramInfoLog(p));
		GL20.glDeleteShader(vs);
		GL20.glDeleteShader(fs);
		return p;
	}

	private static int texture(int internal, int w, int h, int format, int type, ByteBuffer data) {
		int t = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, data);
		return t;
	}

	private static void check(String what) {
		int e = GL11.glGetError();
		if (e == GL11.GL_OUT_OF_MEMORY) throw new IllegalStateException("the graphics card ran out of memory (" + what + ")");
		if (e != GL11.GL_NO_ERROR) throw new IllegalStateException("OpenGL error 0x" + Integer.toHexString(e) + " (" + what + ")");
	}

	// ------------------------------------------------------------------------------ Llm
	@Override
	public String name() {
		return name;
	}

	/** The rows of a matrix go into a texture side by side, as many as fit in a line of WIDTH texels. */
	@Override
	public int upload(int rows, int cols, byte[] q, float[] scale) throws Exception {
		if (cols % 32 != 0) throw new IllegalArgumentException("columns must be a multiple of 32");
		return run(() -> {
			int cols4 = cols / 4, blocks = cols / 32, perLine = Math.max(1, WIDTH / cols4);
			if (cols4 > maxSize) throw new IllegalStateException("rows too long for a texture");
			int lines = (rows + perLine - 1) / perLine;
			if (lines > maxSize) throw new IllegalStateException("matrix too tall for a texture");
			ByteBuffer w = MemoryUtil.memCalloc(perLine * cols4 * 4 * lines);
			ByteBuffer s = MemoryUtil.memCalloc(perLine * blocks * 4 * lines);
			try {
				w.put(q).clear();                                           // rows are already in texture order
				s.asFloatBuffer().put(scale);
				int wt = texture(GL30.GL_RGBA8I, perLine * cols4, lines, GL30.GL_RGBA_INTEGER, GL11.GL_BYTE, w);
				int st = texture(GL30.GL_R32F, perLine * blocks, lines, GL30.GL_RED, GL11.GL_FLOAT, s);
				check("uploading the model");
				bytes += (long) w.capacity() + s.capacity();
				matrices.add(new Matrix(wt, st, rows, cols, perLine));
				return matrices.size() - 1;
			} finally {
				MemoryUtil.memFree(w);
				MemoryUtil.memFree(s);
			}
		});
	}

	@Override
	public void matmul(int handle, float[] x, int n, float[] out) throws Exception {
		run(() -> {
			Matrix m = matrices.get(handle);
			int cols4 = m.cols / 4, total = n * m.rows;
			if (cols4 > xW || n > xH) {                                  // room for the input
				if (xTex != 0) GL11.glDeleteTextures(xTex);
				xW = Math.max(xW, cols4);
				xH = Math.max(xH, Math.max(n, Llm.BATCH));
				xTex = texture(GL30.GL_RGBA32F, xW, xH, GL11.GL_RGBA, GL11.GL_FLOAT, null);
				if (xBuf != null) MemoryUtil.memFree(xBuf);
				xBuf = MemoryUtil.memAllocFloat(xW * xH * 4);
			}
			int w = Math.min(total, WIDTH), h = (total + w - 1) / w;
			if (w > outW || h > outH) {                                   // room for the output
				if (outTex != 0) GL11.glDeleteTextures(outTex);
				outW = Math.max(outW, w);
				outH = Math.max(outH, h);
				outTex = texture(GL30.GL_R32F, outW, outH, GL30.GL_RED, GL11.GL_FLOAT, null);
				GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
				GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, outTex, 0);
				if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
					throw new IllegalStateException("can't draw into a float texture");
				}
				if (outBuf != null) MemoryUtil.memFree(outBuf);
				outBuf = MemoryUtil.memAllocFloat(outW * outH);
			}
			xBuf.clear();
			xBuf.put(x, 0, n * m.cols).flip();
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, xTex);
			GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, cols4, n, GL11.GL_RGBA, GL11.GL_FLOAT, xBuf);
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
			GL11.glViewport(0, 0, w, h);
			GL20.glUseProgram(program);
			GL20.glUniform1i(uCols4, cols4);
			GL20.glUniform1i(uBlocks, m.cols / 32);
			GL20.glUniform1i(uPerLine, m.perLine);
			GL20.glUniform1i(uRows, m.rows);
			GL20.glUniform1i(uN, n);
			GL20.glUniform1i(uOutW, w);
			bind(0, m.weights);
			bind(1, m.scales);
			bind(2, xTex);
			GL30.glBindVertexArray(vao);
			GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
			outBuf.clear();
			GL11.glReadPixels(0, 0, w, h, GL30.GL_RED, GL11.GL_FLOAT, outBuf);   // waits for the GPU
			check("working out a product");
			outBuf.get(out, 0, total);
			return null;
		});
	}

	private static void bind(int unit, int tex) {
		GL15.glActiveTexture(GL15.GL_TEXTURE0 + unit);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
	}

	/** Graphics memory it holds, in MB. */
	public long megabytes() {
		return bytes >> 20;
	}

	@Override
	public void close() {
		try {
			run(() -> {
				for (Matrix m : matrices) {
					GL11.glDeleteTextures(m.weights);
					GL11.glDeleteTextures(m.scales);
				}
				matrices.clear();
				if (xTex != 0) GL11.glDeleteTextures(xTex);
				if (outTex != 0) GL11.glDeleteTextures(outTex);
				if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
				if (vao != 0) GL30.glDeleteVertexArrays(vao);
				if (program != 0) GL20.glDeleteProgram(program);
				if (xBuf != null) MemoryUtil.memFree(xBuf);
				if (outBuf != null) MemoryUtil.memFree(outBuf);
				xBuf = outBuf = null;
				GL.setCapabilities(null);
				window.release();
				return null;
			});
		} catch (Exception ignored) {
			// it's going away anyway
		}
		gl.shutdownNow();
		try {
			onMainThread(() -> {
				window.destroy();
				return null;
			});
		} catch (RuntimeException ignored) {
			// the game is closing
		}
	}

	// --------------------------------------------------------------------------- the window
	/**
	 * A hidden 1x1 window with its own OpenGL 3.3 context, from whichever windowing library the game has (called by
	 * reflection, so one jar works with both).
	 */
	abstract static class Window {
		static String problem = "";

		abstract void makeCurrent();

		abstract void release();

		abstract void destroy();

		/** On the main thread. */
		static Window open() {
			try {
				Class.forName("org.lwjgl.glfw.GLFW");
				return Glfw.create();
			} catch (ClassNotFoundException ignored) {
				// no GLFW: 26.3 and newer use SDL
			} catch (Throwable e) {
				problem = "GLFW: " + e;
				return null;
			}
			try {
				Class.forName("org.lwjgl.sdl.SDLVideo");
				return Sdl.create();
			} catch (ClassNotFoundException e) {
				problem = "neither GLFW nor SDL";
			} catch (Throwable e) {
				problem = "SDL: " + e;
			}
			return null;
		}

		static Object call(Class<?> c, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
			return c.getMethod(name, types).invoke(null, args);
		}

		static int constant(Class<?> c, String name) throws ReflectiveOperationException {
			return ((Number) c.getField(name).get(null)).intValue();
		}
	}

	/** GLFW (Minecraft up to 26.2). */
	static final class Glfw extends Window {
		private final Class<?> g;
		private final long handle;

		private Glfw(Class<?> g, long handle) {
			this.g = g;
			this.handle = handle;
		}

		static Window create() throws ReflectiveOperationException {
			Class<?> g = Class.forName("org.lwjgl.glfw.GLFW");
			Class<?>[] ii = {int.class, int.class};
			call(g, "glfwDefaultWindowHints", new Class<?>[0]);
			call(g, "glfwWindowHint", ii, constant(g, "GLFW_VISIBLE"), constant(g, "GLFW_FALSE"));
			call(g, "glfwWindowHint", ii, constant(g, "GLFW_CLIENT_API"), constant(g, "GLFW_OPENGL_API"));
			call(g, "glfwWindowHint", ii, constant(g, "GLFW_CONTEXT_VERSION_MAJOR"), 3);
			call(g, "glfwWindowHint", ii, constant(g, "GLFW_CONTEXT_VERSION_MINOR"), 3);
			call(g, "glfwWindowHint", ii, constant(g, "GLFW_OPENGL_PROFILE"), constant(g, "GLFW_OPENGL_CORE_PROFILE"));
			call(g, "glfwWindowHint", ii, constant(g, "GLFW_OPENGL_FORWARD_COMPAT"), constant(g, "GLFW_TRUE"));
			long handle = (long) call(g, "glfwCreateWindow", new Class<?>[] {int.class, int.class, CharSequence.class, long.class, long.class},
					1, 1, "Xen GPU", 0L, 0L);
			call(g, "glfwDefaultWindowHints", new Class<?>[0]);
			if (handle == 0) {
				problem = "GLFW couldn't make an OpenGL 3.3 context";
				return null;
			}
			return new Glfw(g, handle);
		}

		@Override
		void makeCurrent() {
			try {
				call(g, "glfwMakeContextCurrent", new Class<?>[] {long.class}, handle);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		void release() {
			try {
				call(g, "glfwMakeContextCurrent", new Class<?>[] {long.class}, 0L);
			} catch (ReflectiveOperationException ignored) {
				// nothing to release
			}
		}

		@Override
		void destroy() {
			try {
				call(g, "glfwDestroyWindow", new Class<?>[] {long.class}, handle);
			} catch (ReflectiveOperationException ignored) {
				// already gone
			}
		}
	}

	/** SDL (Minecraft 26.3 and newer). */
	static final class Sdl extends Window {
		private final Class<?> v;
		private final long window, context;

		private Sdl(Class<?> v, long window, long context) {
			this.v = v;
			this.window = window;
			this.context = context;
		}

		static Window create() throws ReflectiveOperationException {
			Class<?> v = Class.forName("org.lwjgl.sdl.SDLVideo");
			Class<?>[] ii = {int.class, int.class};
			long prevWindow = (long) call(v, "SDL_GL_GetCurrentWindow", new Class<?>[0]);
			long prevContext = (long) call(v, "SDL_GL_GetCurrentContext", new Class<?>[0]);
			call(v, "SDL_GL_SetAttribute", ii, constant(v, "SDL_GL_CONTEXT_MAJOR_VERSION"), 3);
			call(v, "SDL_GL_SetAttribute", ii, constant(v, "SDL_GL_CONTEXT_MINOR_VERSION"), 3);
			call(v, "SDL_GL_SetAttribute", ii, constant(v, "SDL_GL_CONTEXT_PROFILE_MASK"), constant(v, "SDL_GL_CONTEXT_PROFILE_CORE"));
			call(v, "SDL_GL_SetAttribute", ii, constant(v, "SDL_GL_CONTEXT_FLAGS"), constant(v, "SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG"));
			call(v, "SDL_GL_SetAttribute", ii, constant(v, "SDL_GL_SHARE_WITH_CURRENT_CONTEXT"), 0);
			long flags = ((Number) v.getField("SDL_WINDOW_OPENGL").get(null)).longValue() | ((Number) v.getField("SDL_WINDOW_HIDDEN").get(null)).longValue();
			long window = (long) call(v, "SDL_CreateWindow", new Class<?>[] {CharSequence.class, int.class, int.class, long.class}, "Xen GPU", 1, 1, flags);
			if (window == 0) {
				problem = "SDL couldn't make a window";
				return null;
			}
			long context = (long) call(v, "SDL_GL_CreateContext", new Class<?>[] {long.class}, window);
			call(v, "SDL_GL_MakeCurrent", new Class<?>[] {long.class, long.class}, prevWindow, prevContext);   // the game's context back
			if (context == 0) {
				call(v, "SDL_DestroyWindow", new Class<?>[] {long.class}, window);
				problem = "SDL couldn't make an OpenGL 3.3 context";
				return null;
			}
			return new Sdl(v, window, context);
		}

		@Override
		void makeCurrent() {
			try {
				call(v, "SDL_GL_MakeCurrent", new Class<?>[] {long.class, long.class}, window, context);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		void release() {
			try {
				call(v, "SDL_GL_MakeCurrent", new Class<?>[] {long.class, long.class}, window, 0L);
			} catch (ReflectiveOperationException ignored) {
				// nothing to release
			}
		}

		@Override
		void destroy() {
			try {
				call(v, "SDL_GL_DestroyContext", new Class<?>[] {long.class}, context);
				call(v, "SDL_DestroyWindow", new Class<?>[] {long.class}, window);
			} catch (ReflectiveOperationException ignored) {
				// already gone
			}
		}
	}
}
