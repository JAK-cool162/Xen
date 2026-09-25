package xen.mod.client;

import net.minecraft.client.Minecraft;
import xen.mod.XenMod;
import xen.mod.talk.Chat;
import xen.mod.talk.Llm;

import java.util.Locale;

/**
 * A check of the GPU path in a real game client ({@code -Dxen.gpuTest=<model file>}): the same prompt through the chat
 * model on the CPU and on the GPU, the logits compared, the same greedy answer from both, and how long each took.
 */
final class GpuCheck {
	private GpuCheck() {}

	static void run(String modelPath, Minecraft client) {
		try {
			Llm llm = new Llm(modelPath, 3, 1024);
			String prompt = Chat.persona() + "<|im_start|>user\nNotes: You feel calm. You carry 3 logs. You saw a tree 5 blocks away.\n"
					+ "Steve says: hi, what are you up to?<|im_end|>\n<|im_start|>assistant\n";
			int tokens = llm.tokenizer.encode(prompt).size();
			long t0 = System.nanoTime();
			float[] cpu = llm.logitsAfter(prompt);
			double cpuRead = (System.nanoTime() - t0) / 1e9;
			t0 = System.nanoTime();
			String cpuText = llm.generate(prompt, 16, 0f, 1f, 1);
			double cpuAll = (System.nanoTime() - t0) / 1e9;
			XenMod.LOG.info("xen gpu test: CPU read {} tokens in {} s; answer in {} s: {}", tokens, f(cpuRead), f(cpuAll), cpuText);

			Llm.Accelerator a = GlAccelerator.create(true, s -> XenMod.LOG.info("xen gpu test: {}", s));
			if (a == null) {
				XenMod.LOG.info("xen gpu test: no GPU");
				return;
			}
			t0 = System.nanoTime();
			boolean ok = llm.useGpu(a);
			XenMod.LOG.info("xen gpu test: {} took the model: {} in {} s ({} MB) {}", a.name(), ok, f((System.nanoTime() - t0) / 1e9),
					a instanceof GlAccelerator g ? g.megabytes() : -1, llm.gpuProblem);
			if (!ok) return;
			t0 = System.nanoTime();
			float[] gpu = llm.logitsAfter(prompt);
			double gpuRead = (System.nanoTime() - t0) / 1e9;
			double diff = 0, big = 0;
			int bestCpu = 0, bestGpu = 0;
			for (int i = 0; i < cpu.length; i++) {
				diff = Math.max(diff, Math.abs(cpu[i] - gpu[i]));
				big = Math.max(big, Math.abs(cpu[i]));
				if (cpu[i] > cpu[bestCpu]) bestCpu = i;
				if (gpu[i] > gpu[bestGpu]) bestGpu = i;
			}
			t0 = System.nanoTime();
			String gpuText = llm.generate(prompt, 16, 0f, 1f, 1);
			double gpuAll = (System.nanoTime() - t0) / 1e9;
			XenMod.LOG.info("xen gpu test: GPU read {} tokens in {} s; answer in {} s: {}", tokens, f(gpuRead), f(gpuAll), gpuText);
			XenMod.LOG.info("xen gpu test: logits differ by at most {} (largest {}); next token CPU {} GPU {}; same answer: {}",
					f(diff), f(big), bestCpu, bestGpu, cpuText.equals(gpuText));
			llm.close();
		} catch (Throwable e) {
			XenMod.LOG.error("xen gpu test failed", e);
		} finally {
			XenMod.LOG.info("xen gpu test: done");
			client.execute(client::stop);
		}
	}

	private static String f(double v) {
		return String.format(Locale.ROOT, "%.3f", v);
	}
}
