package com.mooo.hairyone.td5tester.events;

public class VehicleInfoEvent {
    public final String vin;
    public final String ecuType;
    public final String mapVariant;

    public VehicleInfoEvent(String vin, String ecuType, String mapVariant) {
        this.vin = vin;
        this.ecuType = ecuType;
        this.mapVariant = mapVariant;
    }
}
