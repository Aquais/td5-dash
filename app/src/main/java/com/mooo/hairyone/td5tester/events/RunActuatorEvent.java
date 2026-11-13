package com.mooo.hairyone.td5tester.events;

import com.mooo.hairyone.td5tester.Requests;

public class RunActuatorEvent {
    public final Requests.RequestPidEnum pid;
    public final String label;

    public RunActuatorEvent(Requests.RequestPidEnum pid, String label) {
        this.pid = pid;
        this.label = label;
    }
}
