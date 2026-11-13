package com.mooo.hairyone.td5tester;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Reference values come from the gauge notes published with the original app
 * and from readings observed on a running engine.
 */
public class ConversionsTest {

    private static final double EPS = 1e-6;

    private static short word(int hi, int lo) {
        return Util.bytes2short((byte) hi, (byte) lo);
    }

    // --- raw word assembly -------------------------------------------------

    @Test public void assemblesUnsignedWord() {
        assertEquals(0x0C80, word(0x0C, 0x80));
    }

    @Test public void assemblesNegativeWord() {
        // Power balance is signed, so the high bit must carry a sign.
        assertEquals(-2, word(0xFF, 0xFE));
    }

    // --- temperatures ------------------------------------------------------

    @Test public void convertsCoolantTemperature() {
        // 0x0C80 = 3200 tenths of a kelvin = 46.8 C
        assertEquals(46.8, Conversions.temperatureCelsius(word(0x0C, 0x80)), EPS);
    }

    @Test public void convertsFreezingPoint() {
        assertEquals(0.0, Conversions.temperatureCelsius((short) 2732), EPS);
    }

    @Test public void convertsSubZeroTemperature() {
        // Below freezing must stay negative rather than clamp to zero.
        assertEquals(-10.0, Conversions.temperatureCelsius((short) 2632), EPS);
    }

    @Test public void convertsNormalOperatingTemperature() {
        // 85 C, the middle of the 82-92 band the notes give.
        assertEquals(85.0, Conversions.temperatureCelsius((short) 3582), EPS);
    }

    // --- voltage -----------------------------------------------------------

    @Test public void convertsBatteryVoltage() {
        assertEquals(13.8, Conversions.volts((short) 13800), EPS);
    }

    @Test public void convertsRestingBatteryVoltage() {
        assertEquals(12.6, Conversions.volts((short) 12600), EPS);
    }

    @Test public void convertsPedalTrackVoltage() {
        // 0x0168 = 360, the low end of the 0.7-4.3 V track range.
        assertEquals(0.36, Conversions.volts(word(0x01, 0x68)), EPS);
    }

    // --- pressures and air -------------------------------------------------

    @Test public void convertsAmbientPressure() {
        assertEquals(99.2, Conversions.pressureKpa((short) 9920), EPS);
    }

    @Test public void convertsAirFlow() {
        assertEquals(57.0, Conversions.airFlowKgH((short) 570), EPS);
    }

    @Test public void computesBoostFromMeasuredAmbient() {
        // 1.1 bar of boost, the stock maximum.
        assertEquals(1.1, Conversions.boostBar(209.2, 99.2), EPS);
    }

    @Test public void readsZeroBoostAtRest() {
        assertEquals(0.0, Conversions.boostBar(99.2, 99.2), EPS);
    }

    @Test public void doesNotReportNegativeBoost() {
        // Vacuum on overrun is not boost, and must not drive the gauge backwards.
        assertEquals(0.0, Conversions.boostBar(80.0, 99.2), EPS);
    }

    @Test public void boostNeedsBothPressures() {
        assertTrue(Conversions.isNoReading(Conversions.boostBar(Double.NaN, 99.2)));
        assertTrue(Conversions.isNoReading(Conversions.boostBar(209.2, Double.NaN)));
    }

    @Test public void boostIgnoresAltitude() {
        // At 1500 m the atmosphere is near 85 kPa; a fixed 100 kPa would
        // have reported 0.15 bar of boost on an idling engine.
        assertEquals(0.0, Conversions.boostBar(85.0, 85.0), EPS);
    }

    // --- pedal -------------------------------------------------------------

    @Test public void convertsPedalTravel() {
        // The notes say 5 on this channel means 50 percent of pedal.
        assertEquals(50.0, Conversions.pedalPercent(5.0), EPS);
        assertEquals(100.0, Conversions.pedalPercent(10.0), EPS);
    }

    // --- dead sensors ------------------------------------------------------

    @Test public void flagsDisconnectedAirMeter() {
        assertTrue(Conversions.isNoReading(Conversions.airFlowOrNoReading(0.0)));
        assertTrue(Conversions.isNoReading(Conversions.airFlowOrNoReading(4.4)));
    }

    @Test public void keepsRealAirFlow() {
        assertEquals(57.0, Conversions.airFlowOrNoReading(57.0), EPS);
        assertEquals(700.0, Conversions.airFlowOrNoReading(700.0), EPS);
    }

    @Test public void flagsMissingAirboxSensor() {
        // 197 C is what a 10P reports, having no three-wire sensor fitted.
        assertTrue(Conversions.isNoReading(Conversions.externalTemperatureOrNoReading(197.0)));
    }

    @Test public void keepsPlausibleOutsideTemperature() {
        assertEquals(18.0, Conversions.externalTemperatureOrNoReading(18.0), EPS);
        assertEquals(-15.0, Conversions.externalTemperatureOrNoReading(-15.0), EPS);
        assertFalse(Conversions.isNoReading(Conversions.externalTemperatureOrNoReading(35.0)));
    }

    // --- fuelling ----------------------------------------------------------

    @Test public void convertsInjectionQuantity() {
        assertEquals(5.0, Conversions.fuelQuantityMg((short) 500), EPS);
    }

    @Test public void convertsAirMass() {
        assertEquals(410.0, Conversions.airMassMg((short) 4100), EPS);
    }

    @Test public void convertsAirFuelRatio() {
        assertEquals(14.2, Conversions.airFuelRatio((short) 1420), EPS);
    }

    // --- power balance -----------------------------------------------------

    @Test public void keepsPowerBalanceSigned() {
        // The old gauge clamped at zero and hid every pulling cylinder.
        assertEquals(-3.0, Conversions.powerBalance((short) -3), EPS);
        assertEquals(2.0, Conversions.powerBalance((short) 2), EPS);
    }

    @Test public void coversTheFullToleranceBand() {
        assertEquals(-15.0, Conversions.powerBalance((short) -15), EPS);
        assertEquals(15.0, Conversions.powerBalance((short) 15), EPS);
    }

    // --- vehicle identity --------------------------------------------------

    @Test public void decodesVinFromAscii() {
        byte[] r = new byte[]{0x30, 0x5A, (byte) 0x87,
                'S', 'A', 'L', 'L', 'D', 'V', 'B', 'P', '7', 'Y', 'A', '1', '2', '3', '4', '5', '6',
                0x00, 0x00};
        assertEquals("SALLDVBP7YA123456", Conversions.identityText(r, 3, 20));
    }

    @Test public void dropsPaddingAroundIdentity() {
        byte[] r = new byte[]{0, 0, 0, ' ', 'N', 'N', 'N', '5', '0', '0', 0x00, ' '};
        assertEquals("NNN500", Conversions.identityText(r, 3, 12));
    }

    @Test public void fallsBackToHexWhenNotText() {
        byte[] r = new byte[]{0, 0, 0, (byte) 0xDE, (byte) 0xAD, (byte) 0xBE};
        assertEquals("DEADBE", Conversions.identityText(r, 3, 6));
    }

    @Test public void survivesAnOutOfRangeWindow() {
        byte[] r = new byte[]{1, 2, 3};
        assertEquals("", Conversions.identityText(r, 3, 99));
        assertEquals("", Conversions.identityText(null, 0, 4));
        assertEquals("", Conversions.identityText(r, 2, 2));
    }

    // --- fault codes -------------------------------------------------------

    @Test public void reportsNoFaultOnAClearEcu() {
        byte[] r = new byte[Faults.FIRST_BYTE + Faults.BYTE_COUNT];
        assertTrue(Faults.decode(r).isEmpty());
    }

    @Test public void decodesASingleFault() {
        byte[] r = new byte[Faults.FIRST_BYTE + Faults.BYTE_COUNT];
        r[Faults.FIRST_BYTE] = (byte) 0b00000001;
        java.util.List<Faults.Fault> found = Faults.decode(r);
        assertEquals(1, found.size());
        assertEquals("01-01", found.get(0).code());
    }

    @Test public void decodesEveryBitOfAByte() {
        byte[] r = new byte[Faults.FIRST_BYTE + Faults.BYTE_COUNT];
        r[Faults.FIRST_BYTE + 2] = (byte) 0xFF;
        java.util.List<Faults.Fault> found = Faults.decode(r);
        assertEquals(8, found.size());
        assertEquals("03-01", found.get(0).code());
        assertEquals("03-08", found.get(7).code());
    }

    @Test public void ignoresATruncatedResponse() {
        assertTrue(Faults.decode(new byte[10]).isEmpty());
        assertTrue(Faults.decode(null).isEmpty());
    }

    @Test public void labelsEveryDecodedFault() {
        byte[] r = new byte[Faults.FIRST_BYTE + Faults.BYTE_COUNT];
        for (int i = 0; i < Faults.BYTE_COUNT; i++) r[Faults.FIRST_BYTE + i] = (byte) 0xFF;
        java.util.List<Faults.Fault> found = Faults.decode(r);
        assertEquals(Faults.BYTE_COUNT * 8, found.size());
        for (Faults.Fault fault : found) {
            assertFalse("code sans libellé : " + fault.code(), fault.label == null);
        }
    }
}
