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

public final class Type4Tag {

    public static final byte[] NDEF_APPLICATION_ID = {
            (byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01
    };

    public static final int FILE_ID_CAPABILITY_CONTAINER = 0xE103;
    public static final int FILE_ID_NDEF = 0xE104;
    public static final int CAPABILITY_CONTAINER_LENGTH = 15;

    public static final int CLA = 0x00;
    public static final int INS_SELECT = 0xA4;
    public static final int INS_READ_BINARY = 0xB0;
    public static final int INS_UPDATE_BINARY = 0xD6;

    public static final int P1_SELECT_APPLICATION = 0x04;
    public static final int P2_SELECT_APPLICATION = 0x00;
    public static final int P1_SELECT_FILE = 0x00;
    public static final int P2_SELECT_FILE = 0x0C;

    public static final int SW_SUCCESS = 0x9000;
    public static final int SW_FILE_NOT_FOUND = 0x6A82;
    public static final int SW_WRONG_PARAMETERS = 0x6B00;
    public static final int SW_INSTRUCTION_NOT_SUPPORTED = 0x6D00;

    public static final int WRITE_ACCESS_GRANTED = 0x00;
    public static final int WRITE_ACCESS_DENIED = 0xFF;

    public static final int NLEN_LENGTH = 2;

    private Type4Tag() {
    }
}
