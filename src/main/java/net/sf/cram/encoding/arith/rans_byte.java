package net.sf.cram.encoding.arith;

import static org.junit.Assert.assertArrayEquals;

import java.nio.ByteBuffer;
import java.util.Random;

public class rans_byte {
	private static final int RANS_BYTE_L = (1 << 23);

	// Description for a symbol
	static class RansSymbol {
		int start; // Start of range
		int freq; // Frequency of symbol (=size of range)
		long rcp_freq; // Reciprocal frequency
		int rcp_shift; // Reciprocal shift
	};

	// Initialize a rANS encoder.
	static int RansEncInit() {
		return RANS_BYTE_L;
	}

	// Encodes a given symbol. This is faster than straight RansEnc since we can
	// do
	// multiplications instead of a divide.
	static int RansEncPutSymbol(int r, ByteBuffer pptr, RansSymbol sym, int scale_bits) {
		// System.out.printf("%d, %d, %d, %d, %d\n", r, sym.freq, sym.rcp_freq,
		// sym.rcp_shift, sym.start);

		// renormalize
		if (r < 0)
			throw new RuntimeException();
		int x_max = ((RANS_BYTE_L >> scale_bits) << 8) * sym.freq; // this
																	// turns
		// into a shift.
		if (r >= x_max) {
			do {
				// System.out.println("Issuing " + (r & 0xff));
				pptr.put((byte) (r & 0xff));
				r >>>= 8;
			} while (r >= x_max);
		}

		// x = C(s,x)
		if (sym.freq == 1)
			r = (r << scale_bits) + sym.start;
		else {
			// written strangely so the compiler generates a multiply high, but
			// only
			// uses 32-bit shifts.
			final int q = (int) (((r * sym.rcp_freq) >>> 32) >> sym.rcp_shift);
			final int p = r - q * sym.freq;
			r = (((q << scale_bits) + sym.start + p));
			// System.out.println(q << scale_bits);
			// System.out.println((q << scale_bits) + sym.start);
			// System.out.printf("x=%d, q=%d, p=%d, scale_bits=%d\n", r, q, p,
			// scale_bits);
		}

		return r;
	}

	static int RansDecAdvance(int r, ByteBuffer pptr, int start, int freq, int scale_bits) {
		int mask = (1 << scale_bits) - 1;

		// s, x = D(x)
		r = freq * (r >> scale_bits) + (r & mask) - start;

		// renormalize
		if (r < RANS_BYTE_L) {
			do {
				int b = 0xFF & pptr.get();
				// System.out.printf("read: %d\n", b);
				r = (r << 8) | b;
			} while (r < RANS_BYTE_L);

		}

		// System.out.printf("r/start/freq/scale_bits: %d, %d, %d, %d\n", r,
		// start, freq, scale_bits);
		return r;
	}

	private static class SymbolStats {
		int[] freqs = new int[256];
		int[] cum_freqs = new int[257];

		void count_freqs(ByteBuffer in, int nbytes) {
			for (int i = 0; i < 256; i++)
				freqs[i] = 0;

			for (int i = 0; i < nbytes; i++)
				freqs[0xFF & in.get()]++;
		};

		void calc_cum_freqs() {
			cum_freqs[0] = 0;
			for (int i = 0; i < 256; i++)
				cum_freqs[i + 1] = cum_freqs[i] + freqs[i];
		};

		void normalize_freqs(int target_total) {
			assert (target_total >= 256);

			calc_cum_freqs();
			int cur_total = cum_freqs[256];

			// resample distribution based on cumulative freqs
			for (int i = 1; i <= 256; i++)
				cum_freqs[i] = (int) (((long) target_total * cum_freqs[i]) / cur_total);

			// if we nuked any non-0 frequency symbol to 0, we need to steal
			// the range to make the frequency nonzero from elsewhere.
			//
			// this is not at all optimal, i'm just doing the first thing that
			// comes to mind.
			for (int i = 0; i < 256; i++) {
				if (freqs[i] != 0 && cum_freqs[i + 1] == cum_freqs[i]) {
					// symbol i was set to zero freq

					// find best symbol to steal frequency from (try to steal
					// from low-freq ones)
					int best_freq = Integer.MAX_VALUE;
					// int best_freq = ~0u;
					int best_steal = -1;
					for (int j = 0; j < 256; j++) {
						int freq = cum_freqs[j + 1] - cum_freqs[j];
						if (freq > 1 && freq < best_freq) {
							best_freq = freq;
							best_steal = j;
						}
					}
					assert (best_steal != -1);

					// and steal from it!
					if (best_steal < i) {
						for (int j = best_steal + 1; j <= i; j++)
							cum_freqs[j]--;
					} else {
						assert (best_steal > i);
						for (int j = i + 1; j <= best_steal; j++)
							cum_freqs[j]++;
					}
				}
			}

			// calculate updated freqs and make sure we didn't screw anything up
			assert (cum_freqs[0] == 0 && cum_freqs[256] == target_total);
			for (int i = 0; i < 256; i++) {
				if (freqs[i] == 0)
					assert (cum_freqs[i + 1] == cum_freqs[i]);
				else
					assert (cum_freqs[i + 1] > cum_freqs[i]);

				// calc updated freq
				freqs[i] = cum_freqs[i + 1] - cum_freqs[i];
			}
		}
	};

	// Initializes a symbol to start "start" and frequency "freq"
	static private void RansSymbolInit(RansSymbol s, int start, int freq) {
		s.start = start;
		s.freq = freq;
		if (freq < 2) // 0 is unsupported (div by zero!) and 1 requires special
						// care
			s.rcp_freq = s.rcp_shift = 0;
		else {
			// Alverson, "Integer Division using reciprocals"
			// shift=ceil(log2(freq))
			int shift = 0;
			while (freq > (1 << shift))
				shift++;

			s.rcp_freq = (((1l << (shift + 31)) + freq - 1) / freq);
			s.rcp_shift = shift - 1;
			if (s.rcp_freq > Integer.MAX_VALUE)
				System.out.println(s.rcp_freq);
		}
	}

	// Flushes the rANS encoder.
	static void RansEncFlush(long r, ByteBuffer pptr) {
		if (r != (int) r)
			throw new RuntimeException("" + r);
		pptr.putInt(0, (int) r);
	}

	// Initializes a rANS decoder.
	// Unlike the encoder, the decoder works forwards as you'd expect.
	static int RansDecInit(ByteBuffer pptr) {
		return pptr.getInt();
	}

	// Returns the current cumulative frequency (map it to a symbol yourself!)
	static int RansDecGet(long r, int scale_bits) {
		return (int) (r & ((1 << scale_bits) - 1));
	}

	// Equivalent to RansDecAdvance that takes a symbol.
	static int RansDecAdvanceSymbol(int r, ByteBuffer pptr, RansSymbol sym, int scale_bits) {
		return RansDecAdvance(r, pptr, sym.start, sym.freq, scale_bits);
	}

	public static void main(String[] args) {
		for (int i = 0; i < 10; i++)
			test();
	}

	private static void test() {
		int in_size = 10 * 1000 * 1000;
		byte[] data = new byte[in_size];
		in_size = data.length;
		Random random = new Random();
		// for (int i = 0; i < in_size; i++)
		// data[i] = (byte) (random.nextInt(30) + 'A');
		random.nextBytes(data);
		ByteBuffer in_bytes = ByteBuffer.wrap(data);

		int prob_bits = 14;
		int prob_scale = 1 << prob_bits;

		SymbolStats stats = new SymbolStats();
		stats.count_freqs(in_bytes, in_size);
		in_bytes.rewind();
		stats.normalize_freqs(prob_scale);

		// cumlative->symbol table
		// this is super brute force
		byte[] cum2sym = new byte[prob_scale];
		for (int s = 0; s < 256; s++)
			for (int i = stats.cum_freqs[s]; i < stats.cum_freqs[s + 1]; i++)
				cum2sym[i] = (byte) s;

		int out_max_size = 32 << 20; // 32MB
		ByteBuffer out_buf = ByteBuffer.allocate(2 * in_size);
		ByteBuffer dec_bytes = ByteBuffer.allocate(in_size);

		// try rANS encode
		RansSymbol[] syms = new RansSymbol[256];

		for (int i = 0; i < 256; i++) {
			syms[i] = new RansSymbol();
			RansSymbolInit(syms[i], stats.cum_freqs[i], stats.freqs[i]);
		}

		// ---- regular rANS encode/decode. Typical usage.
		{
			int rans;
			out_buf.clear();
			long start = System.nanoTime();
			rans = RansEncInit();
			out_buf.position(4);
			for (int i = data.length - 1; i >= 0; i--) {
				int s = 0xFF & data[i];
				// System.out.printf("%d, %c, %d\n", in_bytes.position(), (char)
				// s, syms[s].start);
				rans = RansEncPutSymbol(rans, out_buf, syms[s], prob_bits);
			}
			RansEncFlush(rans, out_buf);
			long end = System.nanoTime();
			System.out.printf("Compression: %d bytes, %.2f b/b, %.2f ms.\n", in_size, ((float) out_buf.position())
					/ in_size, (end - start) / 1000000f);
		}

		out_buf.flip();

		// try rANS decode
		{
			int rans = RansDecInit(out_buf);
			byte[] rd = new byte[out_buf.remaining()];
			for (int i = rd.length - 1; i >= 0; i--) {
				rd[i] = out_buf.get();
			}
			ByteBuffer reverseBuf = ByteBuffer.allocate(rd.length);
			reverseBuf.put(rd);
			reverseBuf.flip();
			long start = System.nanoTime();
			for (int i = 0; i < in_size; i++) {
				byte s = cum2sym[RansDecGet(rans, prob_bits)];
				dec_bytes.put(s);
				// if (s != in_bytes.get(in_size - 1 - i))
				// System.out.printf("%d, %c\n", i, (char) s);
				rans = RansDecAdvanceSymbol(rans, reverseBuf, syms[0xFF & s], prob_bits);
			}
			long end = System.nanoTime();
			System.out.printf("Decompression: %.2f ms.\n", (end - start) / 1000000f);
		}
		in_bytes.rewind();
		dec_bytes.flip();
		assertArrayEquals(in_bytes.array(), dec_bytes.array());
	}
}
