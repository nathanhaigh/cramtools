package net.sf.cram.encoding;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import net.sf.cram.EncodingID;
import net.sf.cram.EncodingParams;

import org.junit.Test;

public class TestByteArrayLenEncoding {

	@Test
	public void testNull() {
		EncodingParams lenParams = NullEncoding.toParam();

		EncodingParams byteParams = NullEncoding.toParam();

		EncodingParams p = ByteArrayLenEncoding.toParam(lenParams, byteParams);

		assertEquals(EncodingID.BYTE_ARRAY_LEN, p.id);
		assertArrayEquals(new byte[] { 0, 0, 0, 0 }, p.params);
	}

	@Test
	public void test() {
		int[] values = new int[] { 1, 2, 3 };
		int[] bitLens = new int[] { 1, 2, 2 };
		EncodingParams lenParams = HuffmanIntegerEncoding.toParam(values,
				bitLens);

		int externalID = 4;
		EncodingParams byteParams = ExternalByteArrayEncoding
				.toParam(externalID);

		EncodingParams p = ByteArrayLenEncoding.toParam(lenParams, byteParams);

		byte[] expected = new byte[] { 0x03, 0x08, 0x03, 0x01, 0x02, 0x03,
				0x03, 0x01, 0x02, 0x02, 0x01, 0x01, 0x04, };
		assertEquals(EncodingID.BYTE_ARRAY_LEN, p.id);
		assertArrayEquals(expected, p.params);

		Encoding<byte[]> encoding = new EncodingFactory()
				.createByteArrayEncoding(p.id);
		encoding.fromByteArray(p.params);
		assertEquals(ByteArrayLenEncoding.class, encoding.getClass());

		ByteArrayLenEncoding bale = (ByteArrayLenEncoding) encoding;
		assertEquals(HuffmanIntegerEncoding.class, bale.lenEncoding.getClass());
		HuffmanIntegerEncoding hie = (HuffmanIntegerEncoding) bale.lenEncoding;
		assertArrayEquals(values, hie.values);
		assertArrayEquals(bitLens, hie.bitLengths);

		assertEquals(ExternalByteArrayEncoding.class,
				bale.byteEncoding.getClass());
		ExternalByteArrayEncoding ebae = (ExternalByteArrayEncoding) bale.byteEncoding;
		assertEquals(externalID, ebae.contentId);
	}
}
