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

import java.io.InputStream;
import java.util.Map;


import net.sf.cram.io.ByteBufferUtils;
import net.sf.cram.io.ExposedByteArrayOutputStream;
import net.sf.cram.structure.EncodingID;
import net.sf.cram.structure.EncodingParams;

public class ExternalIntegerEncoding implements Encoding<Integer> {
	public static final EncodingID encodingId = EncodingID.EXTERNAL ;
	public int contentId = -1 ;

	public ExternalIntegerEncoding() {
	}
	
	public static EncodingParams toParam(int contentId) {
		ExternalIntegerEncoding e = new ExternalIntegerEncoding() ;
		e.contentId = contentId ;
		return new EncodingParams(encodingId, e.toByteArray()) ;
	}

	public byte[] toByteArray() {
		return ByteBufferUtils.writeUnsignedITF8(contentId) ;
	}

	public void fromByteArray(byte[] data) {
		contentId = ByteBufferUtils.readUnsignedITF8(data) ;
	}

	@Override
	public BitCodec<Integer> buildCodec(Map<Integer, InputStream> inputMap,
			Map<Integer, ExposedByteArrayOutputStream> outputMap) {
		InputStream is = inputMap == null ? null : inputMap.get(contentId) ;
		ExposedByteArrayOutputStream os = outputMap == null ? null : outputMap.get(contentId) ;
		return (BitCodec) new ExternalIntegerCodec(os, is);
	}

	@Override
	public EncodingID id() {
		return encodingId;
	}

}
