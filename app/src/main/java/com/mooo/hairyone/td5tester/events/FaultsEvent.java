package com.mooo.hairyone.td5tester.events;

import com.mooo.hairyone.td5tester.Faults;

import java.util.List;

public class FaultsEvent {
    public final List<Faults.Fault> faults;

    public FaultsEvent(List<Faults.Fault> faults) {
        this.faults = faults;
    }
}
