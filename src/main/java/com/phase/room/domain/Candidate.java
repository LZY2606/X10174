package com.phase.room.domain;

public final class Candidate {
    public long id;
    public long eventId;
    public long stationId;
    public String phase;
    public Long segmentId;
    public double arrivalMs;
    public double ciLowMs;
    public double ciHighMs;
    public String polarity;
    public double weight;
    public String weightSource;
    public String status;
    public Long mergedInto;

    public Candidate copy() {
        Candidate c = new Candidate();
        c.id = id;
        c.eventId = eventId;
        c.stationId = stationId;
        c.phase = phase;
        c.segmentId = segmentId;
        c.arrivalMs = arrivalMs;
        c.ciLowMs = ciLowMs;
        c.ciHighMs = ciHighMs;
        c.polarity = polarity;
        c.weight = weight;
        c.weightSource = weightSource;
        c.status = status;
        c.mergedInto = mergedInto;
        return c;
    }
}
