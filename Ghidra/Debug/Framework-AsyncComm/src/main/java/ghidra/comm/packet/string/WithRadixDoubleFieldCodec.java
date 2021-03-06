/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.comm.packet.string;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.CharBuffer;

import ghidra.comm.packet.Packet;
import ghidra.comm.packet.PacketFactory;
import ghidra.comm.packet.err.PacketDecodeException;
import ghidra.comm.packet.err.PacketEncodeException;
import ghidra.comm.packet.fields.AbstractFieldCodec;
import ghidra.comm.packet.fields.FieldCodec;

/**
 * The {@link FieldCodec} for {@code double} fields annotated by {@link WithRadix}
 * 
 * @see WithRadixWrapperFactory
 */
public class WithRadixDoubleFieldCodec
		extends AbstractFieldCodec<StringBuilder, CharBuffer, Double> {
	private final int radix;
	private final StringPacketCodec strCodec;
	private final FieldCodec<StringBuilder, CharBuffer, String> chainNext;

	public WithRadixDoubleFieldCodec(Class<? extends Packet> pktType, Field field, int radix,
			StringPacketCodec strCodec, FieldCodec<StringBuilder, CharBuffer, String> chainNext) {
		super(pktType, field);
		this.radix = radix;
		this.strCodec = strCodec;
		this.chainNext = chainNext;
	}

	@Override
	public Double decodeField(Packet pkt, CharBuffer buf, Integer count, PacketFactory factory)
			throws IOException, PacketDecodeException {
		CharBuffer part = CharBuffer.wrap(chainNext.decodeField(pkt, buf, null, factory));
		Double result = strCodec.decodeDouble(field, part);
		buf.position(buf.position() - part.remaining());
		return result;
	}

	@Override
	public void encodeField(StringBuilder buf, Packet pkt, Double val)
			throws IOException, PacketEncodeException {
		StringBuilder part = strCodec.newEncodeBuffer();
		strCodec.encodeDouble(part, field, val, radix == 16);
		chainNext.encodeField(buf, pkt, part.toString());
	}
}
