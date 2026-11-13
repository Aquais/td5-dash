package com.mooo.hairyone.td5tester;

import java.util.ArrayList;
import java.util.List;

/** Decodes the fault-code bitmap the ECU returns for request 0x21 0x3B. */
public final class Faults {

    /** 35 bytes of flags, one bit per fault, starting at offset 3 in the response. */
    public static final int FIRST_BYTE = 3;
    public static final int BYTE_COUNT = 35;

    private Faults() { }

    public static class Fault {
        public final int group;
        public final int bit;
        public final String label;

        Fault(int group, int bit, String label) {
            this.group = group;
            this.bit = bit;
            this.label = label;
        }

        public String code() {
            return String.format("%02d-%02d", group, bit);
        }
    }

    public static List<Fault> decode(byte[] response) {
        List<Fault> faults = new ArrayList<>();
        if (response == null || response.length < FIRST_BYTE + BYTE_COUNT) return faults;
        for (int i = 0; i < BYTE_COUNT; i++) {
            byte flags = response[i + FIRST_BYTE];
            for (int bit = 0; bit < 8; bit++) {
                if (((flags >> bit) & 1) != 1) continue;
                int index = (i * 8) + bit;
                String label = index < FaultCodes.faultCodeList.length
                        ? FaultCodes.faultCodeList[index]
                        : "";
                faults.add(new Fault(i + 1, bit + 1, label));
            }
        }
        return faults;
    }
}
