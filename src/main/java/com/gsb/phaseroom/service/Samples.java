package com.gsb.phaseroom.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Little-endian double encoding for immutable raw sample storage. */
public final class Samples {

    private Samples() {
    }

    public static byte[] encode(List<Double> values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.size() * Double.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (Double value : values) {
            buffer.putDouble(value);
        }
        return buffer.array();
    }

    public static double[] decode(byte[] blob) {
        ByteBuffer buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        double[] values = new double[blob.length / Double.BYTES];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getDouble();
        }
        return values;
    }
}
