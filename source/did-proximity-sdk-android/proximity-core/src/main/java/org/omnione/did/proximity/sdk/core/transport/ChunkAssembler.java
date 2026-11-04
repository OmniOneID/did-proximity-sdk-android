/*
 * Copyright 2026 OmniOne.
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

package org.omnione.did.proximity.sdk.core.transport;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.ProximityException;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class ChunkAssembler {

    public static final byte MORE_COMING = 0x01;
    public static final byte LAST = 0x00;

    public static final int MAX_MESSAGE_BYTES = 1024 * 1024;
    public static final int MAX_ATTRIBUTE_VALUE_BYTES = 512;

    private static final int ATT_HEADER = 3;

    public static int maxChunkSize(int mtu) {
        return Math.min(mtu - ATT_HEADER, MAX_ATTRIBUTE_VALUE_BYTES);
    }

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    @NonNull
    public static List<byte[]> split(@NonNull byte[] message, int maxChunkSize) {
        if (maxChunkSize < 2) {
            throw new ProximityException("maxChunkSize must be at least 2, was " + maxChunkSize);
        }
        if (message.length > MAX_MESSAGE_BYTES) {
            throw new ProximityException("message of " + message.length
                    + " bytes exceeds " + MAX_MESSAGE_BYTES);
        }
        int payloadSize = maxChunkSize - 1;
        List<byte[]> chunks = new ArrayList<>();
        int offset = 0;
        do {
            int length = Math.min(payloadSize, message.length - offset);
            boolean last = offset + length >= message.length;
            byte[] chunk = new byte[length + 1];
            chunk[0] = last ? LAST : MORE_COMING;
            System.arraycopy(message, offset, chunk, 1, length);
            chunks.add(chunk);
            offset += length;
        } while (offset < message.length);
        return chunks;
    }

    @Nullable
    public byte[] append(@NonNull byte[] chunk) {
        if (chunk.length < 1) {
            buffer.reset();
            throw new ProximityException("empty chunk");
        }
        byte flag = chunk[0];
        if (flag != MORE_COMING && flag != LAST) {
            buffer.reset();
            throw new ProximityException("unknown chunk flag 0x"
                    + Integer.toHexString(flag & 0xFF));
        }
        if (chunk.length - 1 > MAX_MESSAGE_BYTES - buffer.size()) {
            buffer.reset();
            throw new ProximityException("assembled message exceeds "
                    + MAX_MESSAGE_BYTES + " bytes");
        }
        buffer.write(chunk, 1, chunk.length - 1);
        if (flag == MORE_COMING) {
            return null;
        }
        byte[] message = buffer.toByteArray();
        buffer.reset();
        return message;
    }
}
