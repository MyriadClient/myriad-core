package dev.myriadclient.impl.network;


/** Shared state between {@code Packets} and the connection mixin. */
public final class PacketGate {
	private static final ThreadLocal<int[]> SILENT = ThreadLocal.withInitial(() -> new int[1]);

	private PacketGate() {
	}

	/** True while a packet is being sent with {@code Packets.sendSilently}: events are skipped for it. */
	public static boolean isSilent() {
		return SILENT.get()[0] > 0;
	}

	public static void runSilently(Runnable send) {
		int[] depth = SILENT.get();
		depth[0]++;
		try {
			send.run();
		} finally {
			depth[0]--;
		}
	}

}
