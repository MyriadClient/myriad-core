package dev.myriadclient.impl.update;

import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads a jar the way the game's loader does, through its central directory, and refuses any jar that could read
 * differently to another tool: two entries with one name, a local header that disagrees with the directory, names that
 * aren't plain paths, encrypted, multi-disk or ZIP64 archives, entries outside the archive. So what's checked before an
 * update goes in is what will load.
 */
final class JarEntries {
	private static final int EOCD = 0x06054b50, CENTRAL = 0x02014b50, LOCAL = 0x04034b50, ZIP64_LOCATOR = 0x07064b50;
	private static final int MAX_ENTRIES = 65_534;

	record Entry(String name, int method, long compressedSize, long size, long crc, long dataStart) {
	}

	private final byte[] zip;
	private final ByteBuffer buf;
	private final Map<String, Entry> entries = new LinkedHashMap<>();

	private JarEntries(byte[] zip) {
		this.zip = zip;
		this.buf = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
	}

	/** The jar's entries, or an IOException saying why it isn't a jar this can vouch for. */
	static JarEntries read(byte[] zip) throws IOException {
		JarEntries j = new JarEntries(zip);
		j.parse();
		return j;
	}

	Map<String, Entry> entries() {
		return entries;
	}

	/** Entry {@code name}'s content, or null when there's no such entry; refused when it's larger than {@code max}. */
	byte @Nullable [] content(String name, int max) throws IOException {
		Entry e = entries.get(name);
		if (e == null) return null;
		if (e.size() > max) throw new IOException(name + " is too large");
		byte[] out;
		if (e.method() == 0) {
			if (e.compressedSize() != e.size()) throw new IOException(name + " has inconsistent sizes");
			out = new byte[(int) e.size()];
			System.arraycopy(zip, (int) e.dataStart(), out, 0, out.length);
		} else {
			Inflater inf = new Inflater(true);
			try {
				inf.setInput(zip, (int) e.dataStart(), (int) e.compressedSize());
				ByteArrayOutputStream o = new ByteArrayOutputStream((int) Math.min(e.size(), 1 << 16));
				byte[] chunk = new byte[8192];
				while (!inf.finished()) {
					int n = inf.inflate(chunk);
					if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw new IOException(name + " is cut short");
					o.write(chunk, 0, n);
					if (o.size() > e.size()) throw new IOException(name + " is larger than it says");
				}
				out = o.toByteArray();
			} catch (DataFormatException ex) {
				throw new IOException(name + " is corrupt", ex);
			} finally {
				inf.end();
			}
			if (out.length != e.size()) throw new IOException(name + " is smaller than it says");
		}
		CRC32 crc = new CRC32();
		crc.update(out);
		if (crc.getValue() != e.crc()) throw new IOException(name + " fails its checksum");
		return out;
	}

	private void parse() throws IOException {
		int len = zip.length;
		if (len < 22) throw new IOException("not a jar");
		int eocd = -1;
		// The last end record is the one: it must account for the rest of the file exactly, or the jar is refused
		// (rather than searching on for an earlier one, which another reader wouldn't).
		for (int p = len - 22; p >= Math.max(0, len - 22 - 0xFFFF); p--) {
			if (buf.getInt(p) == EOCD) {
				eocd = p;
				break;
			}
		}
		if (eocd < 0) throw new IOException("not a jar");
		if (eocd + 22 + u16(eocd + 20) != len) throw new IOException("the jar's end record doesn't match its length");
		// A second end record hidden in the comment would let another reader find another directory.
		for (int p = eocd + 22; p + 4 <= len; p++) {
			if (buf.getInt(p) == EOCD || buf.getInt(p) == CENTRAL || buf.getInt(p) == ZIP64_LOCATOR) throw new IOException("the jar's comment hides zip records");
		}
		if (eocd >= 20 && buf.getInt(eocd - 20) == ZIP64_LOCATOR) throw new IOException("ZIP64 jars aren't taken");
		int disk = u16(eocd + 4), cdDisk = u16(eocd + 6), onDisk = u16(eocd + 8), total = u16(eocd + 10);
		long cdSize = u32(eocd + 12), cdOffset = u32(eocd + 16);
		if (disk != 0 || cdDisk != 0 || onDisk != total) throw new IOException("multi-part jars aren't taken");
		if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) throw new IOException("ZIP64 jars aren't taken");
		if (total > MAX_ENTRIES || cdOffset + cdSize > eocd) throw new IOException("the jar's directory is broken");
		int p = (int) cdOffset;
		for (int i = 0; i < total; i++) {
			need(p, 46, cdOffset + cdSize);
			if (buf.getInt(p) != CENTRAL) throw new IOException("the jar's directory is broken");
			int flags = u16(p + 8), method = u16(p + 10);
			long crc = u32(p + 16), csize = u32(p + 20), usize = u32(p + 24);
			int nameLen = u16(p + 28), extraLen = u16(p + 30), commentLen = u16(p + 32);
			long local = u32(p + 42);
			need(p + 46, nameLen + extraLen + commentLen, cdOffset + cdSize);
			String name = name(p + 46, nameLen);
			if ((flags & 1) != 0) throw new IOException("encrypted jars aren't taken");
			if (method != 0 && method != 8) throw new IOException(name + " uses an unknown compression");
			if (csize == 0xFFFFFFFFL || usize == 0xFFFFFFFFL || local == 0xFFFFFFFFL) throw new IOException("ZIP64 jars aren't taken");
			if (entries.containsKey(name)) throw new IOException("the jar has two entries named " + name);
			// The local header must be this entry's, and its data inside the archive before the directory.
			need((int) Math.min(local, Integer.MAX_VALUE), 30, cdOffset);
			int l = (int) local;
			if (buf.getInt(l) != LOCAL) throw new IOException("the jar's entries don't match its directory");
			int lNameLen = u16(l + 26), lExtraLen = u16(l + 28);
			need(l + 30, lNameLen + lExtraLen, cdOffset);
			if (lNameLen != nameLen || u16(l + 8) != method || !name(l + 30, lNameLen).equals(name)) {
				throw new IOException("the jar's entries don't match its directory");
			}
			long data = (long) l + 30 + lNameLen + lExtraLen;
			if (data + csize > cdOffset) throw new IOException("the jar's entries run past its end");
			entries.put(name, new Entry(name, method, csize, usize, crc, data));
			p += 46 + nameLen + extraLen + commentLen;
		}
	}

	/** An entry name: UTF-8, a relative path of plain segments (no {@code .}, {@code ..}, empty segments, {@code \} or NUL). */
	private String name(int at, int len) throws IOException {
		String n;
		try {
			n = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(zip, at, len)).toString();
		} catch (CharacterCodingException e) {
			throw new IOException("the jar has an unreadable entry name");
		}
		String path = n.endsWith("/") ? n.substring(0, n.length() - 1) : n;
		if (path.isEmpty() || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) throw new IOException("the jar has an odd entry name");
		for (String seg : path.split("/", -1)) {
			if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) throw new IOException("the jar has an odd entry name");
		}
		return n;
	}

	private void need(int at, int len, long end) throws IOException {
		if (at < 0 || len < 0 || (long) at + len > end) throw new IOException("the jar is cut short");
	}

	private int u16(int at) {
		return buf.getShort(at) & 0xFFFF;
	}

	private long u32(int at) {
		return buf.getInt(at) & 0xFFFFFFFFL;
	}
}
