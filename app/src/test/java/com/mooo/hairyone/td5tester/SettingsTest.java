package com.mooo.hairyone.td5tester;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/** The threshold registry has to stay coherent, or a gauge colours wrongly. */
public class SettingsTest {

    @Test public void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Settings.Entry entry : Settings.ENTRIES) {
            assertTrue("clé en double : " + entry.key, seen.add(entry.key));
        }
    }

    @Test public void everyEntryIsLabelled() {
        for (Settings.Entry entry : Settings.ENTRIES) {
            assertFalse(entry.label.isEmpty());
            assertFalse(entry.section.isEmpty());
        }
    }

    @Test public void warningThresholdsSitInsideAlertThresholds() {
        assertTrue(factory("rpm_warn") < factory("rpm_alert"));
        assertTrue(factory("coolant_warn") < factory("coolant_alert"));
        assertTrue(factory("turbo_warn") < factory("turbo_alert"));
        assertTrue(factory("maf_warn") < factory("maf_alert"));
        assertTrue(factory("fuel_warn") < factory("fuel_alert"));
        assertTrue(factory("inlet_warn") < factory("inlet_alert"));
    }

    @Test public void twoSidedBandsAreOrdered() {
        assertTrue(factory("volt_alert_low") < factory("volt_warn_low"));
        assertTrue(factory("volt_warn_low") < factory("volt_warn_high"));
        assertTrue(factory("volt_warn_high") < factory("volt_alert_high"));

        assertTrue(factory("inj_alert_low") < factory("inj_warn_low"));
        assertTrue(factory("inj_warn_low") < factory("inj_warn_high"));
        assertTrue(factory("inj_warn_high") < factory("inj_alert_high"));

        assertTrue(factory("track_alert_low") < factory("track_warn_low"));
        assertTrue(factory("track_warn_low") < factory("track_warn_high"));
        assertTrue(factory("track_warn_high") < factory("track_alert_high"));
    }

    @Test public void coldIsBelowWarm() {
        assertTrue(factory("cold_c") < factory("warm_c"));
        assertTrue(factory("coolant_cold") < factory("coolant_warn"));
    }

    @Test public void factoryValuesMatchThePublishedNotes() {
        assertEquals(1.42f, factory("turbo_alert"), 0.001f);
        assertEquals(105f, factory("coolant_alert"), 0.001f);
        assertEquals(-4f, factory("inj_warn_low"), 0.001f);
        assertEquals(4f, factory("inj_warn_high"), 0.001f);
        assertEquals(0.7f, factory("track_warn_low"), 0.001f);
        assertEquals(4.3f, factory("track_warn_high"), 0.001f);
    }

    private static float factory(String key) {
        for (Settings.Entry entry : Settings.ENTRIES) {
            if (entry.key.equals(key)) return entry.factory;
        }
        throw new IllegalArgumentException(key);
    }
}
