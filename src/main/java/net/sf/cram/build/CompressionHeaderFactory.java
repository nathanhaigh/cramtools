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
package net.sf.cram.build;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.sf.cram.common.MutableInt;
import net.sf.cram.common.Utils;
import net.sf.cram.encoding.BetaIntegerEncoding;
import net.sf.cram.encoding.BitCodec;
import net.sf.cram.encoding.ByteArrayLenEncoding;
import net.sf.cram.encoding.ByteArrayStopEncoding;
import net.sf.cram.encoding.Encoding;
import net.sf.cram.encoding.ExternalByteArrayEncoding;
import net.sf.cram.encoding.ExternalByteEncoding;
import net.sf.cram.encoding.ExternalIntegerEncoding;
import net.sf.cram.encoding.GammaIntegerEncoding;
import net.sf.cram.encoding.HuffmanByteEncoding;
import net.sf.cram.encoding.HuffmanIntegerEncoding;
import net.sf.cram.encoding.NullEncoding;
import net.sf.cram.encoding.SubexpIntegerEncoding;
import net.sf.cram.encoding.read_features.Deletion;
import net.sf.cram.encoding.read_features.HardClip;
import net.sf.cram.encoding.read_features.Padding;
import net.sf.cram.encoding.read_features.ReadFeature;
import net.sf.cram.encoding.read_features.RefSkip;
import net.sf.cram.encoding.read_features.Substitution;
import net.sf.cram.huffman.HuffmanCode;
import net.sf.cram.huffman.HuffmanTree;
import net.sf.cram.structure.CompressionHeader;
import net.sf.cram.structure.CramRecord;
import net.sf.cram.structure.EncodingKey;
import net.sf.cram.structure.EncodingParams;
import net.sf.cram.structure.ReadTag;
import net.sf.cram.structure.SubstitutionMatrix;
import net.sf.picard.util.Log;

public class CompressionHeaderFactory {
	private static final Charset charset = Charset.forName("US-ASCII");
	private static Log log = Log.getInstance(CompressionHeaderFactory.class);
	private static final int oqz = ReadTag.nameType3BytesToInt("OQ", 'Z');
	private static final int bqz = ReadTag.nameType3BytesToInt("OQ", 'Z');

	public CompressionHeader build(List<CramRecord> records, SubstitutionMatrix substitutionMatrix) {
		CompressionHeader h = new CompressionHeader();
		h.externalIds = new ArrayList<Integer>();
		int exCounter = 0;

		int baseID = exCounter++;
		h.externalIds.add(baseID);

		int qualityScoreID = exCounter++;
		h.externalIds.add(qualityScoreID);

		int readNameID = exCounter++;
		h.externalIds.add(readNameID);

		int mateInfoID = exCounter++;
		h.externalIds.add(mateInfoID);

		int tagValueExtID = exCounter++;
		h.externalIds.add(tagValueExtID);

		log.debug("Assigned external id to bases: " + baseID);
		log.debug("Assigned external id to quality scores: " + qualityScoreID);
		log.debug("Assigned external id to read names: " + readNameID);
		log.debug("Assigned external id to mate info: " + mateInfoID);
		log.debug("Assigned external id to tag values: " + tagValueExtID);

		h.eMap = new TreeMap<EncodingKey, EncodingParams>();
		for (EncodingKey key : EncodingKey.values())
			h.eMap.put(key, NullEncoding.toParam());

		h.tMap = new TreeMap<Integer, EncodingParams>();

		{ // bit flags encoding:
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				calculator.add(r.flags);
			calculator.calculate();
			h.eMap.put(EncodingKey.BF_BitFlags,
					HuffmanIntegerEncoding.toParam(calculator.values(), calculator.bitLens()));
		}

		{ // compression bit flags encoding:
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				calculator.add(r.getCompressionFlags());
			calculator.calculate();
			h.eMap.put(EncodingKey.CF_CompressionBitFlags,
					HuffmanByteEncoding.toParam(calculator.valuesAsBytes(), calculator.bitLens()));
		}

		{ // ref id:
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				calculator.add(r.sequenceId);
			calculator.calculate();
			h.eMap.put(EncodingKey.RI_RefId, HuffmanIntegerEncoding.toParam(calculator.values(), calculator.bitLens()));
		}

		{ // read length encoding:
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				calculator.add(r.readLength);
			calculator.calculate();

			h.eMap.put(EncodingKey.RL_ReadLength,
					HuffmanIntegerEncoding.toParam(calculator.values(), calculator.bitLens()));
		}

		{ // alignment offset:
			IntegerEncodingCalculator calc = new IntegerEncodingCalculator("alignment offset", 0);
			for (CramRecord r : records) {
				calc.addValue(r.alignmentDelta);
			}

			Encoding<Integer> bestEncoding = calc.getBestEncoding();
			h.eMap.put(EncodingKey.AP_AlignmentPositionOffset,
					new EncodingParams(bestEncoding.id(), bestEncoding.toByteArray()));
		}

		{ // read group
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				calculator.add(r.readGroupID);
			calculator.calculate();

			h.eMap.put(EncodingKey.RG_ReadGroup,
					HuffmanIntegerEncoding.toParam(calculator.values(), calculator.bitLens()));
		}

		{ // read name encoding:
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				calculator.add(r.readName.length());
			calculator.calculate();

			h.eMap.put(EncodingKey.RN_ReadName, ByteArrayLenEncoding.toParam(
					HuffmanIntegerEncoding.toParam(calculator.values(), calculator.bitLens()),
					ExternalByteArrayEncoding.toParam(readNameID)));
			// h.eMap.put(EncodingKey.RN_ReadName,
			// ByteArrayStopEncoding.toParam((byte) 0, readNameID));
		}

		{ // records to next fragment
			IntegerEncodingCalculator calc = new IntegerEncodingCalculator("records to next fragment", 0);
			for (CramRecord r : records) {
				if (r.isHasMateDownStream())
					calc.addValue(r.recordsToNextFragment);
			}

			Encoding<Integer> bestEncoding = calc.getBestEncoding();
			h.eMap.put(EncodingKey.NF_RecordsToNextFragment,
					new EncodingParams(bestEncoding.id(), bestEncoding.toByteArray()));
		}

		{ // tag count
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				calculator.add(r.tags == null ? 0 : r.tags.length);
			calculator.calculate();

			h.eMap.put(EncodingKey.TC_TagCount,
					HuffmanIntegerEncoding.toParam(calculator.values(), calculator.bitLens()));
		}

		{ // tag name and type
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records) {
				if (r.tags == null)
					continue;
				for (ReadTag tag : r.tags)
					calculator.add(tag.keyType3BytesAsInt);
			}
			calculator.calculate();

			h.eMap.put(EncodingKey.TN_TagNameAndType,
					HuffmanIntegerEncoding.toParam(calculator.values(), calculator.bitLens()));
		}

		{

			Comparator<ReadTag> comparator = new Comparator<ReadTag>() {

				@Override
				public int compare(ReadTag o1, ReadTag o2) {
					return o1.keyType3BytesAsInt - o2.keyType3BytesAsInt;
				}
			};

			Comparator<byte[]> baComparator = new Comparator<byte[]>() {

				@Override
				public int compare(byte[] o1, byte[] o2) {
					if (o1.length - o2.length != 0)
						return o1.length - o2.length;

					for (int i = 0; i < o1.length; i++)
						if (o1[i] != o2[i])
							return o1[i] - o2[i];

					return 0;
				}
			};

			Map<byte[], MutableInt> map = new TreeMap<byte[], MutableInt>(baComparator);
			MutableInt noTagCounter = new MutableInt();
			map.put(new byte[0], noTagCounter);
			for (CramRecord r : records) {
				if (r.tags == null) {
					noTagCounter.value++;
					r.tagIdsIndex = noTagCounter;
					continue;
				}

				Arrays.sort(r.tags, comparator);
				r.tagIds = new byte[r.tags.length * 3];

				int tagIndex = 0;
				for (int i = 0; i < r.tags.length; i++) {
					r.tagIds[i * 3] = (byte) r.tags[tagIndex].keyType3Bytes.charAt(0);
					r.tagIds[i * 3 + 1] = (byte) r.tags[tagIndex].keyType3Bytes.charAt(1);
					r.tagIds[i * 3 + 2] = (byte) r.tags[tagIndex].keyType3Bytes.charAt(2);
					tagIndex++;
				}

				MutableInt count = map.get(r.tagIds);
				if (count == null) {
					count = new MutableInt();
					map.put(r.tagIds, count);
				}
				count.value++;
				r.tagIdsIndex = count;
			}

			byte[][][] dic = new byte[map.size()][][];
			int i = 0;
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (byte[] idsAsBytes : map.keySet()) {
				int nofIds = idsAsBytes.length / 3;
				dic[i] = new byte[nofIds][];
				for (int j = 0; j < idsAsBytes.length;) {
					int idIndex = j / 3;
					dic[i][idIndex] = new byte[3];
					dic[i][idIndex][0] = idsAsBytes[j++];
					dic[i][idIndex][1] = idsAsBytes[j++];
					dic[i][idIndex][2] = idsAsBytes[j++];
				}
				calculator.add(i, map.get(idsAsBytes).value);
				map.get(idsAsBytes).value = i++;
			}

			calculator.calculate();
			h.eMap.put(EncodingKey.TL_TagIdList,
					HuffmanIntegerEncoding.toParam(calculator.values(), calculator.bitLens()));
			h.dictionary = dic;
		}

		{ // tag values
			Map<Integer, HuffmanParamsCalculator> cc = new TreeMap<Integer, HuffmanParamsCalculator>();

			for (CramRecord r : records) {
				if (r.tags == null)
					continue;

				for (ReadTag tag : r.tags) {
					switch (tag.keyType3BytesAsInt) {
					// case ReadTag.OQZ:
					// case ReadTag.BQZ:
					// EncodingParams params = h.tMap
					// .get(tag.keyType3BytesAsInt);
					// if (params == null) {
					// h.tMap.put(tag.keyType3BytesAsInt,
					// ByteArrayStopEncoding.toParam((byte) 1,
					// tagValueExtID));
					// }
					// break;

					default:
						HuffmanParamsCalculator c = cc.get(tag.keyType3BytesAsInt);
						if (c == null) {
							c = new HuffmanParamsCalculator();
							cc.put(tag.keyType3BytesAsInt, c);
						}
						c.add(tag.getValueAsByteArray().length);
						break;
					}
				}
			}

			if (!cc.isEmpty())
				for (Integer key : cc.keySet()) {
					HuffmanParamsCalculator c = cc.get(key);
					c.calculate();

					h.tMap.put(key, ByteArrayLenEncoding.toParam(
							HuffmanIntegerEncoding.toParam(c.values(), c.bitLens()),
							ExternalByteArrayEncoding.toParam(tagValueExtID)));
				}

			for (Integer key : h.tMap.keySet()) {
				log.debug(String.format("TAG ENCODING: %d, %s", key, h.tMap.get(key)));
			}

			// for (CramRecord r : records) {
			// if (r.tags == null || r.tags.isEmpty())
			// continue;
			// for (ReadTag tag : r.tags) {
			// EncodingParams params = h.tMap.get(tag.keyType3BytesAsInt);
			// if (params == null) {
			// h.tMap.put(tag.keyType3BytesAsInt,
			// ByteArrayStopEncoding.toParam((byte) 0,
			// tagValueExtID));
			// }
			// }
			// }
		}

		{ // number of read features
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				calculator.add(r.readFeatures == null ? 0 : r.readFeatures.size());
			calculator.calculate();

			h.eMap.put(EncodingKey.FN_NumberOfReadFeatures,
					HuffmanIntegerEncoding.toParam(calculator.values(), calculator.bitLens()));
		}

		{ // feature position
			IntegerEncodingCalculator calc = new IntegerEncodingCalculator("read feature position", 0);
			for (CramRecord r : records) {
				int prevPos = 0;
				if (r.readFeatures == null)
					continue;
				for (ReadFeature rf : r.readFeatures) {
					calc.addValue(rf.getPosition() - prevPos);
					prevPos = rf.getPosition();
				}
			}

			Encoding<Integer> bestEncoding = calc.getBestEncoding();
			h.eMap.put(EncodingKey.FP_FeaturePosition,
					new EncodingParams(bestEncoding.id(), bestEncoding.toByteArray()));
		}

		{ // feature code
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				if (r.readFeatures == null)
					continue;
				else
					for (ReadFeature rf : r.readFeatures)
						calculator.add(rf.getOperator());
			calculator.calculate();

			h.eMap.put(EncodingKey.FC_FeatureCode,
					HuffmanByteEncoding.toParam(calculator.valuesAsBytes(), calculator.bitLens));
		}

		{ // bases:
			h.eMap.put(EncodingKey.BA_Base, ExternalByteEncoding.toParam(baseID));
		}

		{ // quality scores:
			// HuffmanParamsCalculator calculator = new
			// HuffmanParamsCalculator();
			// for (CramRecord r : records) {
			// if (r.getQualityScores() == null) {
			// if (r.getReadFeatures() != null) {
			// for (ReadFeature f:r.getReadFeatures()) {
			// switch (f.getOperator()) {
			// case BaseQualityScore.operator:
			// calculator.add(((BaseQualityScore)f).getQualityScore()) ;
			// break;
			// default:
			// break;
			// }
			// }
			// }
			// } else {
			// for (byte s:r.getQualityScores()) calculator.add(s) ;
			// }
			// }
			// calculator.calculate();
			//
			// h.eMap.put(EncodingKey.QS_QualityScore,
			// HuffmanByteEncoding.toParam(
			// calculator.valuesAsBytes(), calculator.bitLens));

			h.eMap.put(EncodingKey.QS_QualityScore, ExternalByteEncoding.toParam(qualityScoreID));
		}

		{ // base substitution code
			if (substitutionMatrix == null) {
				long[][] freqs = new long[200][200];
				for (CramRecord r : records) {
					if (r.readFeatures == null)
						continue;
					else
						for (ReadFeature rf : r.readFeatures)
							if (rf.getOperator() == Substitution.operator) {
								Substitution s = ((Substitution) rf);
								byte refBase = s.getRefernceBase();
								byte base = s.getBase();
								freqs[refBase][base]++;
							}
				}

				h.substitutionMatrix = new SubstitutionMatrix(freqs);
			} else
				h.substitutionMatrix = substitutionMatrix;

			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				if (r.readFeatures == null)
					continue;
				else
					for (ReadFeature rf : r.readFeatures) {
						if (rf.getOperator() == Substitution.operator) {
							Substitution s = ((Substitution) rf);
							if (s.getCode() == -1) {
								byte refBase = s.getRefernceBase();
								byte base = s.getBase();
								s.setCode(h.substitutionMatrix.code(refBase, base));
							}
							calculator.add(s.getCode());
						}
					}
			calculator.calculate();

			h.eMap.put(EncodingKey.BS_BaseSubstitutionCode,
					HuffmanIntegerEncoding.toParam(calculator.values, calculator.bitLens));
		}

		{ // insertion bases
			h.eMap.put(EncodingKey.IN_Insertion, ByteArrayStopEncoding.toParam((byte) 0, baseID));
		}

		{ // insertion bases
			h.eMap.put(EncodingKey.SC_SoftClip, ByteArrayStopEncoding.toParam((byte) 0, baseID));
		}

		{ // deletion length
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				if (r.readFeatures == null)
					continue;
				else
					for (ReadFeature rf : r.readFeatures)
						if (rf.getOperator() == Deletion.operator)
							calculator.add(((Deletion) rf).getLength());
			calculator.calculate();

			h.eMap.put(EncodingKey.DL_DeletionLength,
					HuffmanIntegerEncoding.toParam(calculator.values, calculator.bitLens));
		}

		{ // hard clip length
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				if (r.readFeatures == null)
					continue;
				else
					for (ReadFeature rf : r.readFeatures)
						if (rf.getOperator() == HardClip.operator)
							calculator.add(((HardClip) rf).getLength());
			calculator.calculate();

			h.eMap.put(EncodingKey.HC_HardClip, HuffmanIntegerEncoding.toParam(calculator.values, calculator.bitLens));
		}

		{ // padding length
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				if (r.readFeatures == null)
					continue;
				else
					for (ReadFeature rf : r.readFeatures)
						if (rf.getOperator() == Padding.operator)
							calculator.add(((Padding) rf).getLength());
			calculator.calculate();

			h.eMap.put(EncodingKey.PD_padding, HuffmanIntegerEncoding.toParam(calculator.values, calculator.bitLens));
		}

		{ // ref skip length
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				if (r.readFeatures == null)
					continue;
				else
					for (ReadFeature rf : r.readFeatures)
						if (rf.getOperator() == RefSkip.operator)
							calculator.add(((RefSkip) rf).getLength());
			calculator.calculate();

			h.eMap.put(EncodingKey.RS_RefSkip, HuffmanIntegerEncoding.toParam(calculator.values, calculator.bitLens));
		}

		{ // mapping quality score
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				if (!r.isSegmentUnmapped())
					calculator.add(r.mappingQuality);
			calculator.calculate();

			h.eMap.put(EncodingKey.MQ_MappingQualityScore,
					HuffmanIntegerEncoding.toParam(calculator.values(), calculator.bitLens));
		}

		{ // mate bit flags
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				calculator.add(r.getMateFlags());
			calculator.calculate();

			h.eMap.put(EncodingKey.MF_MateBitFlags,
					HuffmanIntegerEncoding.toParam(calculator.values, calculator.bitLens));
		}

		{ // next fragment ref id:
			HuffmanParamsCalculator calculator = new HuffmanParamsCalculator();
			for (CramRecord r : records)
				if (r.isDetached())
					calculator.add(r.mateSequnceID);
			calculator.calculate();

			if (calculator.values.length == 0)
				h.eMap.put(EncodingKey.NS_NextFragmentReferenceSequenceID, NullEncoding.toParam());

			h.eMap.put(EncodingKey.NS_NextFragmentReferenceSequenceID,
					HuffmanIntegerEncoding.toParam(calculator.values(), calculator.bitLens()));
			log.debug("NS: " + h.eMap.get(EncodingKey.NS_NextFragmentReferenceSequenceID));
		}

		{ // next fragment alignment start
			h.eMap.put(EncodingKey.NP_NextFragmentAlignmentStart, ExternalIntegerEncoding.toParam(mateInfoID));
		}

		{ // template size
			h.eMap.put(EncodingKey.TS_InsetSize, ExternalIntegerEncoding.toParam(mateInfoID));
		}

		{ // test mark
			// h.eMap.put(EncodingKey.TM_TestMark,
			// BetaIntegerEncoding.toParam(0, 32));
		}

		return h;
	}

	private static class BitCode implements Comparable<BitCode> {
		int value;
		int len;

		public BitCode(int value, int len) {
			this.value = value;
			this.len = len;
		}

		@Override
		public int compareTo(BitCode o) {
			int result = value - o.value;
			if (result != 0)
				return result;
			return len - o.len;
		}
	}

	public static class HuffmanParamsCalculator {
		private HashMap<Integer, MutableInt> countMap = new HashMap<Integer, MutableInt>();
		private int[] values = new int[] {};
		private int[] bitLens = new int[] {};

		public void add(int value) {
			MutableInt counter = countMap.get(value);
			if (counter == null) {
				counter = new MutableInt();
				countMap.put(value, counter);
			}
			counter.value++;
		}

		public void add(Integer value, int inc) {
			MutableInt counter = countMap.get(value);
			if (counter == null) {
				counter = new MutableInt();
				countMap.put(value, counter);
			}
			counter.value += inc;
		}

		public int[] bitLens() {
			return bitLens;
		}

		public int[] values() {
			return values;
		}

		public Integer[] valuesAsAutoIntegers() {
			Integer[] ivalues = new Integer[values.length];
			for (int i = 0; i < ivalues.length; i++)
				ivalues[i] = values[i];

			return ivalues;
		}

		public byte[] valuesAsBytes() {
			byte[] bvalues = new byte[values.length];
			for (int i = 0; i < bvalues.length; i++)
				bvalues[i] = (byte) (0xFF & values[i]);

			return bvalues;
		}

		public Byte[] valuesAsAutoBytes() {
			Byte[] bvalues = new Byte[values.length];
			for (int i = 0; i < bvalues.length; i++)
				bvalues[i] = (byte) (0xFF & values[i]);

			return bvalues;
		}

		public void calculate() {
			HuffmanTree<Integer> tree = null;
			{
				int size = countMap.size();
				int[] freqs = new int[size];
				int[] values = new int[size];

				int i = 0;
				for (Integer v : countMap.keySet()) {
					values[i] = v;
					freqs[i] = countMap.get(v).value;
					i++;
				}
				tree = HuffmanCode.buildTree(freqs, Utils.autobox(values));
			}

			List<Integer> valueList = new ArrayList<Integer>();
			List<Integer> lens = new ArrayList<Integer>();
			HuffmanCode.getValuesAndBitLengths(valueList, lens, tree);

			// the following sorting is not really required, but whatever:
			BitCode[] codes = new BitCode[valueList.size()];
			for (int i = 0; i < valueList.size(); i++) {
				codes[i] = new BitCode(valueList.get(i), lens.get(i));
			}
			Arrays.sort(codes);

			values = new int[codes.length];
			bitLens = new int[codes.length];

			for (int i = 0; i < codes.length; i++) {
				BitCode code = codes[i];
				bitLens[i] = code.len;
				values[i] = code.value;
			}
		}
	}

	public static class EncodingLengthCalculator {
		private BitCodec<Integer> codec;
		private Encoding<Integer> encoding;
		private long len;

		public EncodingLengthCalculator(Encoding<Integer> encoding) {
			this.encoding = encoding;
			codec = encoding.buildCodec(null, null);
		}

		public void add(int value) {
			len += codec.numberOfBits(value);
		}

		public void add(int value, int inc) {
			len += inc * codec.numberOfBits(value);
		}

		public long len() {
			return len;
		}
	}

	public static class IntegerEncodingCalculator {
		public List<EncodingLengthCalculator> calcs = new ArrayList<EncodingLengthCalculator>();
		private int max = 0;
		private int count = 0;
		private String name;
		private HashMap<Integer, MutableInt> dictionary = new HashMap<Integer, MutableInt>();
		private int dictionaryThreshold = 100;

		public IntegerEncodingCalculator(String name, int dictionaryThreshold, int minValue) {
			this.name = name;
			// for (int i = 2; i < 10; i++)
			// calcs.add(new EncodingLengthCalculator(
			// new GolombIntegerEncoding(i)));
			//
			// for (int i = 2; i < 20; i++)
			// calcs.add(new EncodingLengthCalculator(
			// new GolombRiceIntegerEncoding(i)));

			calcs.add(new EncodingLengthCalculator(new GammaIntegerEncoding(1 - minValue)));

			for (int i = 2; i < 5; i++)
				calcs.add(new EncodingLengthCalculator(new SubexpIntegerEncoding(0 - minValue, i)));

			if (dictionaryThreshold < 1)
				dictionary = null;
			else {
				dictionary = new HashMap<Integer, MutableInt>();
				// int pow = (int) Math.ceil(Math.log(dictionaryThreshold)
				// / Math.log(2f));
				// dictionaryThreshold = 1 << pow ;
				// dictionary = new HashMap<Integer,
				// MutableInt>(dictionaryThreshold, 1);
			}
		}

		public IntegerEncodingCalculator(String name, int minValue) {
			this(name, 255, minValue);
		}

		public void addValue(int value) {
			count++;
			if (value > max)
				max = value;

			for (EncodingLengthCalculator c : calcs)
				c.add(value);

			if (dictionary != null) {
				if (dictionary.size() >= dictionaryThreshold - 1)
					dictionary = null;
				else {
					MutableInt m = dictionary.get(value);
					if (m == null) {
						m = new MutableInt();
						dictionary.put(value, m);
					}
					m.value++;
				}

			}

		}

		public Encoding<Integer> getBestEncoding() {
			EncodingLengthCalculator bestC = calcs.get(0);

			for (EncodingLengthCalculator c : calcs) {
				if (c.len() < bestC.len())
					bestC = c;
			}

			Encoding<Integer> bestEncoding = bestC.encoding;
			long bits = bestC.len();

			{ // check if beta is better:

				int betaLength = (int) Math.round(Math.log(max) / Math.log(2) + 0.5);
				if (bits > betaLength * count) {
					bestEncoding = new BetaIntegerEncoding(betaLength);
					bits = betaLength * count;
				}
			}

			{ // try huffman:
				if (dictionary != null) {
					HuffmanParamsCalculator c = new HuffmanParamsCalculator();
					for (Integer value : dictionary.keySet())
						c.add(value, dictionary.get(value).value);

					c.calculate();

					EncodingParams param = HuffmanIntegerEncoding.toParam(c.values(), c.bitLens());
					HuffmanIntegerEncoding he = new HuffmanIntegerEncoding();
					he.fromByteArray(param.params);
					EncodingLengthCalculator lc = new EncodingLengthCalculator(he);
					for (Integer value : dictionary.keySet())
						lc.add(value, dictionary.get(value).value);

					if (lc.len() < bits) {
						bestEncoding = he;
						bits = lc.len();
					}
				}
			}

			byte[] params = bestEncoding.toByteArray();
			params = Arrays.copyOf(params, Math.min(params.length, 20));
			log.debug("Best encoding for " + name + ": " + bestEncoding.id().name() + Arrays.toString(params));

			return bestEncoding;
		}
	}
}
