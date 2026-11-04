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

package org.omnione.did.proximity.sdk.core.transport.nfc;

public final class NfcDataTransfer {

    public static final byte[] AID_MDOC_DATA = {
            (byte) 0xA0, 0x00, 0x00, 0x02, 0x48, 0x04, 0x00
    };

    public static final int INS_ENVELOPE = 0xC3;
    public static final int INS_GET_RESPONSE = 0xC0;
    public static final int CLA_CHAIN_LAST = 0x00;
    public static final int CLA_CHAIN_MORE = 0x10;
    public static final int P2_SELECT_DIS = 0x0C;
    public static final int TAG_DO53 = 0x53;

    public static final int SW_MORE_DATA = 0x61;
    public static final int SW_CONDITIONS_NOT_SATISFIED = 0x6985;
    public static final int SW_WRONG_DATA = 0x6A80;
    public static final int SW_CLASS_NOT_SUPPORTED = 0x6E00;

    public static final int TRANSCEIVE_MARGIN = 10;
    public static final int SHORT_MAX_DATA = 255;
    public static final int SHORT_MAX_LE = 256;
    public static final int EXTENDED_MAX_DATA = 65535;
    public static final int EXTENDED_MAX_LE = 65536;

    private NfcDataTransfer() {
    }
}
