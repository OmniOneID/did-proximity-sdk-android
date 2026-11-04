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

package org.omnione.did.proximity.sdk.core.nfc;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class FakeType4Tag implements ApduChannel {

    interface Responder {
        @NonNull
        byte[] onMessage(@NonNull byte[] ndefMessage);
    }

    private final List<String> commands = new ArrayList<>();

    private byte[] ndefFile;
    private Responder responder;
    private boolean writable;
    private int maxTransceiveLength = 261;
    private int applicationStatus = Type4Tag.SW_SUCCESS;
    private int selected = -1;
    private ByteArrayOutputStream pending;
    private boolean nlenReset;

    static byte[] file(@NonNull byte[] ndefMessage) {
        byte[] file = new byte[ndefMessage.length + 2];
        file[0] = (byte) (ndefMessage.length >>> 8);
        file[1] = (byte) ndefMessage.length;
        System.arraycopy(ndefMessage, 0, file, 2, ndefMessage.length);
        return file;
    }

    FakeType4Tag serving(@NonNull byte[] ndefMessage) {
        this.ndefFile = file(ndefMessage);
        return this;
    }

    FakeType4Tag writable(@NonNull Responder responder) {
        this.writable = true;
        this.responder = responder;
        return this;
    }

    FakeType4Tag maxTransceiveLength(int value) {
        this.maxTransceiveLength = value;
        return this;
    }

    FakeType4Tag applicationStatus(int status) {
        this.applicationStatus = status;
        return this;
    }

    List<String> commands() {
        return commands;
    }

    @Override
    public int maxTransceiveLength() {
        return maxTransceiveLength;
    }

    @NonNull
    @Override
    public byte[] transceive(@NonNull byte[] commandApdu) {
        commands.add(Apdu.hex(commandApdu));
        Apdu.Command command = Apdu.parseCommand(commandApdu);

        if (command.ins == Type4Tag.INS_SELECT
                && command.p1 == Type4Tag.P1_SELECT_APPLICATION) {
            if (!Arrays.equals(command.data, Type4Tag.NDEF_APPLICATION_ID)) {
                return Apdu.response(null, Type4Tag.SW_FILE_NOT_FOUND);
            }
            return Apdu.response(null, applicationStatus);
        }
        if (command.ins == Type4Tag.INS_SELECT && command.p1 == Type4Tag.P1_SELECT_FILE) {
            selected = ((command.data[0] & 0xFF) << 8) | (command.data[1] & 0xFF);
            if (selected != Type4Tag.FILE_ID_CAPABILITY_CONTAINER
                    && selected != Type4Tag.FILE_ID_NDEF) {
                return Apdu.response(null, Type4Tag.SW_FILE_NOT_FOUND);
            }
            return Apdu.response(null, Type4Tag.SW_SUCCESS);
        }
        if (command.ins == Type4Tag.INS_READ_BINARY) {
            byte[] source = selected == Type4Tag.FILE_ID_CAPABILITY_CONTAINER
                    ? capabilityContainer()
                    : ndefFile;
            int offset = command.offset();
            int length = Math.min(command.le, source.length - offset);
            if (offset > source.length || length < 0) {
                return Apdu.response(null, Type4Tag.SW_WRONG_PARAMETERS);
            }
            return Apdu.response(Arrays.copyOfRange(source, offset, offset + length),
                    Type4Tag.SW_SUCCESS);
        }
        if (command.ins == Type4Tag.INS_UPDATE_BINARY) {
            if (!writable) {
                return Apdu.response(null, Type4Tag.SW_WRONG_PARAMETERS);
            }
            return update(command);
        }
        return Apdu.response(null, Type4Tag.SW_INSTRUCTION_NOT_SUPPORTED);
    }

    @NonNull
    private byte[] update(@NonNull Apdu.Command command) {
        int offset = command.offset();
        if (offset == 0) {
            int declared = ((command.data[0] & 0xFF) << 8) | (command.data[1] & 0xFF);
            if (declared == 0) {
                pending = new ByteArrayOutputStream();
                nlenReset = true;
                return Apdu.response(null, Type4Tag.SW_SUCCESS);
            }
            if (!nlenReset || pending == null) {
                return Apdu.response(null, Type4Tag.SW_WRONG_PARAMETERS);
            }
            byte[] written = pending.toByteArray();
            pending = null;
            nlenReset = false;
            if (written.length != declared) {
                return Apdu.response(null, Type4Tag.SW_WRONG_PARAMETERS);
            }
            ndefFile = file(responder.onMessage(written));
            return Apdu.response(null, Type4Tag.SW_SUCCESS);
        }
        if (!nlenReset || pending == null) {
            return Apdu.response(null, Type4Tag.SW_WRONG_PARAMETERS);
        }
        pending.write(command.data, 0, command.data.length);
        return Apdu.response(null, Type4Tag.SW_SUCCESS);
    }

    @NonNull
    private byte[] capabilityContainer() {
        return new byte[]{
                0x00, 0x0F, 0x20, 0x7F, (byte) 0xFF, 0x7F, (byte) 0xFF,
                0x04, 0x06, (byte) 0xE1, 0x04, 0x7F, (byte) 0xFF, 0x00,
                (byte) (writable ? Type4Tag.WRITE_ACCESS_GRANTED : Type4Tag.WRITE_ACCESS_DENIED)
        };
    }
}
