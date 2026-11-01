package com.emirchat.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HkdfTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void rfc5869TestCase1() {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");

        byte[] prk = Hkdf.extract(salt, ikm);
        assertArrayEquals(HEX.parseHex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk);

        byte[] okm = Hkdf.expand(prk, info, 42);
        assertArrayEquals(HEX.parseHex(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm);
    }

    @Test
    void rfc5869TestCase3EmptySaltAndInfo() {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] prk = Hkdf.extract(new byte[0], ikm);
        assertArrayEquals(HEX.parseHex("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"), prk);
        assertArrayEquals(HEX.parseHex(
                "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
                Hkdf.expand(prk, new byte[0], 42));
    }

    @Test
    void rejectsInvalidLength() {
        assertThrows(IllegalArgumentException.class, () -> Hkdf.expand(new byte[32], new byte[0], 0));
        assertThrows(IllegalArgumentException.class, () -> Hkdf.expand(new byte[32], new byte[0], 255 * 32 + 1));
    }
}
