package com.example;

/**
 * PCM utility helpers:
 *   - stereoToMono : downmix stereo 48 kHz 16-bit PCM → mono 48 kHz 16-bit PCM
 *   - generateSineWave : synthesise a pure sine at a given frequency
 */
public final class PcmUtils {

    private PcmUtils() {}

    // SVC frame parameters: 20 ms @ 48 000 Hz = 960 samples (mono)
    public static final int SAMPLE_RATE   = 48_000;
    public static final int FRAME_SAMPLES = 960; // 20 ms worth

    /**
     * Downmix interleaved stereo 16-bit little-endian PCM bytes → mono short[].
     *
     * @param stereoBytes raw bytes from Lavaplayer (L0,L1,R0,R1, L0,L1,R0,R1 …)
     * @return mono short[] — same number of samples as stereo had per channel
     */
    public static short[] stereoToMono(byte[] stereoBytes) {
        // Each stereo sample pair = 4 bytes (2 per channel, LE 16-bit)
        int frameCount = stereoBytes.length / 4;
        short[] mono = new short[frameCount];

        for (int i = 0; i < frameCount; i++) {
            int base = i * 4;
            // Little-endian 16-bit sample for left channel
            short left  = (short) ((stereoBytes[base + 1] << 8) | (stereoBytes[base] & 0xFF));
            // Little-endian 16-bit sample for right channel
            short right = (short) ((stereoBytes[base + 3] << 8) | (stereoBytes[base + 2] & 0xFF));
            // Average — keep in [-32768, 32767]
            mono[i] = (short) ((left + right) >> 1);
        }
        return mono;
    }

    /**
     * Generate {@code durationMs} milliseconds of a sine wave at {@code freqHz}.
     * Output is mono 48 kHz 16-bit at 80 % amplitude to avoid clipping.
     *
     * @param freqHz    frequency in Hz  (e.g. 440)
     * @param durationMs duration in milliseconds (e.g. 1000 for 1 second)
     * @return short[] PCM samples, length = SAMPLE_RATE * durationMs / 1000
     */
    public static short[] generateSineWave(double freqHz, int durationMs) {
        int totalSamples = SAMPLE_RATE * durationMs / 1000;
        short[] out = new short[totalSamples];
        double amplitude = Short.MAX_VALUE * 0.8; // 80 %
        for (int i = 0; i < totalSamples; i++) {
            double angle = 2.0 * Math.PI * freqHz * i / SAMPLE_RATE;
            out[i] = (short) (amplitude * Math.sin(angle));
        }
        return out;
    }
}
