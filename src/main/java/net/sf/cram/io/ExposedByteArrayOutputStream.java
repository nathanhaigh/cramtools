package net.sf.cram.io;

import java.io.ByteArrayOutputStream;

public class ExposedByteArrayOutputStream extends ByteArrayOutputStream {
	
	

	public ExposedByteArrayOutputStream(byte[] array) {
		buf = array ;
	}
	
	public ExposedByteArrayOutputStream() {
		super();
	}

	public ExposedByteArrayOutputStream(int size) {
		super(size);
	}

	public byte[] getBuffer() {
		return buf;
	}
}