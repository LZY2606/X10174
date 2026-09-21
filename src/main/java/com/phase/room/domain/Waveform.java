package com.phase.room.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Little-endian float32 sample coding plus content addressing helpers. */
public final class Waveform {
    public static final int BYTES_PER_SAMPLE = 4;

    private Waveform() {
    }

    public static byte[] encode(float[] samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * BYTES_PER_SAMPLE)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : samples) {
            buffer.putFloat(sample);
        }
        return buffer.array();
    }

    public static float[] decode(byte[] bytes) {
        if (bytes.length % BYTES_PER_SAMPLE != 0) {
            throw new IllegalArgumentException("波形字节长度必须是 4 的倍数");
        }
        float[] samples = new float[bytes.length / BYTES_PER_SAMPLE];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return samples;
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A trace counts as clipped once any sample reaches the float headroom. */
    public static boolean detectClipping(float[] samples) {
        for (float sample : samples) {
            if (Math.abs(sample) >= 0.99f) {
                return true;
            }
        }
        return false;
    }
}
