package com.seisjury.domain;

public class FreezeConflictException extends RuntimeException {
    private final ClockConflict.Report report;

    public FreezeConflictException(ClockConflict.Report report) {
        super("freeze blocked: order reversals detected");
        this.report = report;
    }

    public ClockConflict.Report report() {
        return report;
    }
}
