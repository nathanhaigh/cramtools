/*******************************************************************************
 * Copyright 2013 EMBL-EBI
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package net.sf.cram.encoding;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.sf.cram.io.BitInputStream;
import net.sf.cram.io.BitOutputStream;
import net.sf.cram.io.ByteBufferUtils;


public class ExternalIntegerCodec extends AbstractBitCodec<Integer> {
	private OutputStream os;
	private InputStream is;
	private OutputStream nullOS = new OutputStream() {

		@Override
		public void write(byte[] b) throws IOException {
		}

		@Override
		public void write(int b) throws IOException {
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
		}
	};

	public ExternalIntegerCodec(OutputStream os, InputStream is) {
		this.os = os;
		this.is = is;
	}

	@Override
	public Integer read(BitInputStream bis) throws IOException {
		return ByteBufferUtils.readUnsignedITF8(is);
	}

	@Override
	public long write(BitOutputStream bos, Integer value) throws IOException {
		return ByteBufferUtils.writeUnsignedITF8(value, os);
	}

	@Override
	public long numberOfBits(Integer value) {
		try {
			return ByteBufferUtils.writeUnsignedITF8(value, nullOS);
		} catch (IOException e) {
			// this should never happened but still:
			throw new RuntimeException(e) ;
		}
	}

	@Override
	public Integer read(BitInputStream bis, int len) throws IOException {
		throw new RuntimeException("Not implemented.");
	}
}
