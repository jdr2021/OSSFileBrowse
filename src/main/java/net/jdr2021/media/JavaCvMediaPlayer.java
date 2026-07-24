package net.jdr2021.media;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
import net.jdr2021.utils.RuntimePlatform;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.awt.image.BufferedImage;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small JavaCV/FFmpeg playback engine used behind the JavaFX user interface.
 *
 * <p>All container probing and frame decoding happens on one daemon thread.
 * The listener receives decoded {@link BufferedImage} instances and may move
 * them to the JavaFX application thread. Audio is converted to signed
 * little-endian PCM and sent to Java Sound.</p>
 */
public final class JavaCvMediaPlayer implements AutoCloseable {
    private static final long NO_SEEK = Long.MIN_VALUE;
    private static final long PROGRESS_INTERVAL_MICROS = 100_000L;
    private static final long MAX_SLEEP_SLICE_MILLIS = 20L;

    private final String source;
    private final Listener listener;
    private final Object stateLock = new Object();

    private volatile boolean stopRequested;
    private volatile boolean initialized;
    private volatile boolean paused = true;
    private volatile boolean finished;
    private volatile long currentMicros;
    private volatile long durationMicros;
    private final AtomicLong pendingSeekMicros =
            new AtomicLong(NO_SEEK);
    private volatile Thread worker;
    private volatile SourceDataLine audioLine;

    public JavaCvMediaPlayer(String source, Listener listener) {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("媒体地址为空");
        }
        if (listener == null) {
            throw new IllegalArgumentException("媒体监听器为空");
        }
        this.source = source;
        this.listener = listener;
    }

    /**
     * Starts asynchronous native initialization. Playback remains paused after
     * {@link Listener#onReady(MediaInfo)} until {@link #play()} is called.
     */
    public void start() {
        synchronized (stateLock) {
            if (worker != null) {
                return;
            }
            Thread thread = new Thread(this::runDecoder,
                    "oss-preview-javacv");
            thread.setDaemon(true);
            worker = thread;
            thread.start();
        }
    }

    public void play() {
        if (stopRequested) {
            return;
        }
        synchronized (stateLock) {
            if (finished) {
                pendingSeekMicros.set(0L);
                finished = false;
            }
            paused = false;
            stateLock.notifyAll();
        }
        safeNotify(listener::onPlaying);
    }

    public void pause() {
        synchronized (stateLock) {
            paused = true;
            stateLock.notifyAll();
        }
        safeNotify(listener::onPaused);
    }

    public void togglePlayback() {
        if (isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    public void seekSeconds(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds)) {
            return;
        }
        long target = Math.max(0L, Math.round(seconds * 1_000_000.0));
        long duration = durationMicros;
        if (duration > 0L) {
            target = Math.min(target, duration);
        }
        pendingSeekMicros.set(target);
        finished = false;
        synchronized (stateLock) {
            stateLock.notifyAll();
        }
    }

    public boolean isPlaying() {
        return initialized && !paused && !finished && !stopRequested;
    }

    public boolean isReady() {
        return initialized && !stopRequested;
    }

    public long getCurrentMicros() {
        return currentMicros;
    }

    public long getDurationMicros() {
        return durationMicros;
    }

    @Override
    public void close() {
        stopRequested = true;
        synchronized (stateLock) {
            stateLock.notifyAll();
        }
        SourceDataLine line = audioLine;
        if (line != null) {
            try {
                line.stop();
            } finally {
                line.close();
            }
        }
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void runDecoder() {
        FFmpegFrameGrabber grabber = null;
        Java2DFrameConverter converter = null;
        try {
            RuntimePlatform.requireNativeMediaCompatible();
            FFmpegFrameGrabber.tryLoad();
            grabber = createGrabber(source);
            grabber.start();
            if (stopRequested) {
                return;
            }

            durationMicros = Math.max(0L, grabber.getLengthInTime());
            int width = Math.max(0, grabber.getImageWidth());
            int height = Math.max(0, grabber.getImageHeight());
            int channels = Math.max(0, grabber.getAudioChannels());
            int sampleRate = Math.max(0, grabber.getSampleRate());
            SourceDataLine openedAudioLine = openAudioLine(sampleRate, channels);
            audioLine = openedAudioLine;
            converter = new Java2DFrameConverter();

            MediaInfo info = new MediaInfo(
                    safeText(grabber.getFormat()),
                    safeText(grabber.getVideoCodecName()),
                    safeText(grabber.getAudioCodecName()),
                    width, height, grabber.getFrameRate(),
                    sampleRate, channels, durationMicros,
                    channels == 0 || openedAudioLine != null);
            initialized = true;
            safeNotify(() -> listener.onReady(info));

            long clockMediaMicros = 0L;
            long clockWallNanos = System.nanoTime();
            long lastProgressMicros = -PROGRESS_INTERVAL_MICROS;
            boolean decodedMediaFrame = false;

            while (!stopRequested) {
                boolean resumed = waitUntilPlayable();
                if (stopRequested) {
                    break;
                }
                if (resumed) {
                    clockMediaMicros = currentMicros;
                    clockWallNanos = System.nanoTime();
                }

                long seekTarget = takePendingSeek();
                if (seekTarget != NO_SEEK) {
                    grabber.setTimestamp(seekTarget, true);
                    currentMicros = seekTarget;
                    SourceDataLine line = audioLine;
                    if (line != null) {
                        line.flush();
                    }
                    clockMediaMicros = seekTarget;
                    clockWallNanos = System.nanoTime();
                    lastProgressMicros =
                            seekTarget - PROGRESS_INTERVAL_MICROS;
                    long progress = seekTarget;
                    safeNotify(() -> listener.onProgress(
                            progress, durationMicros));
                    if (paused) {
                        continue;
                    }
                }

                SourceDataLine line = audioLine;
                if (line != null && !line.isRunning()) {
                    line.start();
                }

                Frame frame = grabber.grab();
                if (frame == null) {
                    if (!decodedMediaFrame) {
                        throw new IllegalStateException(
                                "FFmpeg 已打开容器，但未读取到音视频帧");
                    }
                    markFinished();
                    continue;
                }
                long timestamp = frame.timestamp >= 0L
                        ? frame.timestamp : grabber.getTimestamp();
                if (!paceFrame(timestamp, clockMediaMicros,
                        clockWallNanos)) {
                    continue;
                }

                if (frame.samples != null && frame.samples.length > 0) {
                    decodedMediaFrame = true;
                    writeAudio(frame.samples,
                            frame.audioChannels > 0
                                    ? frame.audioChannels : channels);
                }
                if (frame.image != null && frame.image.length > 0) {
                    decodedMediaFrame = true;
                    BufferedImage converted = converter.convert(frame);
                    if (converted != null) {
                        safeNotify(() -> listener.onFrame(
                                converted, timestamp));
                    }
                }

                currentMicros = Math.max(0L, timestamp);
                if (currentMicros - lastProgressMicros
                        >= PROGRESS_INTERVAL_MICROS) {
                    lastProgressMicros = currentMicros;
                    long progress = currentMicros;
                    safeNotify(() -> listener.onProgress(
                            progress, durationMicros));
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!stopRequested) {
                safeNotify(() -> listener.onError(interrupted));
            }
        } catch (Throwable failure) {
            if (!stopRequested) {
                safeNotify(() -> listener.onError(failure));
            }
        } finally {
            initialized = false;
            closeAudioLine();
            if (converter != null) {
                converter.close();
            }
            if (grabber != null) {
                try {
                    grabber.stop();
                } catch (Throwable ignored) {
                    // release() below is the final native cleanup path.
                }
                try {
                    grabber.release();
                } catch (Throwable ignored) {
                    // The process can continue with the JavaFX fallback.
                }
            }
            worker = null;
        }
    }

    private static FFmpegFrameGrabber createGrabber(String source) {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(source);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.setTimeout(15_000);
        grabber.setOption("rw_timeout", "15000000");
        if (source.regionMatches(true, 0, "http://", 0, 7)
                || source.regionMatches(true, 0, "https://", 0, 8)) {
            grabber.setOption(
                    "user_agent", "OSSFileBrowse-JavaCV/2.0");
        }
        return grabber;
    }

    private boolean waitUntilPlayable() throws InterruptedException {
        boolean waited = false;
        synchronized (stateLock) {
            while (!stopRequested && paused
                    && pendingSeekMicros.get() == NO_SEEK) {
                waited = true;
                SourceDataLine line = audioLine;
                if (line != null) {
                    line.stop();
                }
                stateLock.wait();
            }
        }
        return waited;
    }

    private boolean paceFrame(long timestamp,
                              long clockMediaMicros,
                              long clockWallNanos)
            throws InterruptedException {
        while (!stopRequested && !paused
                && pendingSeekMicros.get() == NO_SEEK) {
            long mediaDelay = timestamp - clockMediaMicros;
            long elapsed = (System.nanoTime() - clockWallNanos) / 1_000L;
            long remainingMicros = mediaDelay - elapsed;
            if (remainingMicros <= 1_000L) {
                return true;
            }
            Thread.sleep(Math.min(MAX_SLEEP_SLICE_MILLIS,
                    Math.max(1L, remainingMicros / 1_000L)));
        }
        return !stopRequested && !paused
                && pendingSeekMicros.get() == NO_SEEK;
    }

    private long takePendingSeek() {
        return pendingSeekMicros.getAndSet(NO_SEEK);
    }

    private void markFinished() {
        currentMicros = durationMicros > 0L
                ? durationMicros : currentMicros;
        paused = true;
        finished = true;
        SourceDataLine line = audioLine;
        if (line != null) {
            line.drain();
            line.stop();
        }
        safeNotify(() -> listener.onProgress(
                currentMicros, durationMicros));
        safeNotify(listener::onFinished);
    }

    private SourceDataLine openAudioLine(int sampleRate, int channels) {
        if (sampleRate <= 0 || channels <= 0) {
            return null;
        }
        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, 16, channels, channels * 2,
                sampleRate, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        try {
            SourceDataLine line =
                    (SourceDataLine) AudioSystem.getLine(info);
            line.open(format);
            return line;
        } catch (LineUnavailableException
                 | IllegalArgumentException failure) {
            System.err.println("[JavaCV] 音频输出初始化失败，继续显示视频："
                    + failure.getMessage());
            return null;
        }
    }

    private void writeAudio(Buffer[] samples, int channels) {
        SourceDataLine line = audioLine;
        if (line == null || samples == null || samples.length == 0) {
            return;
        }
        byte[] pcm = toPcm16(samples, Math.max(1, channels));
        int offset = 0;
        while (!stopRequested && !paused && offset < pcm.length) {
            offset += line.write(pcm, offset, pcm.length - offset);
        }
    }

    static byte[] toPcm16(Buffer[] samples, int channels) {
        if (samples == null || samples.length == 0) {
            return new byte[0];
        }
        if (samples.length == 1) {
            Buffer sample = samples[0];
            int count = sample.remaining();
            byte[] result = new byte[count * 2];
            for (int i = 0; i < count; i++) {
                short value = sampleAsShort(sample, i);
                result[i * 2] = (byte) (value & 0xff);
                result[i * 2 + 1] = (byte) ((value >>> 8) & 0xff);
            }
            return result;
        }

        int outputChannels = Math.min(Math.max(1, channels),
                samples.length);
        int frames = Integer.MAX_VALUE;
        for (int channel = 0; channel < outputChannels; channel++) {
            frames = Math.min(frames, samples[channel].remaining());
        }
        if (frames == Integer.MAX_VALUE) {
            return new byte[0];
        }
        byte[] result = new byte[frames * outputChannels * 2];
        int output = 0;
        for (int frame = 0; frame < frames; frame++) {
            for (int channel = 0; channel < outputChannels; channel++) {
                short value = sampleAsShort(samples[channel], frame);
                result[output++] = (byte) (value & 0xff);
                result[output++] = (byte) ((value >>> 8) & 0xff);
            }
        }
        return result;
    }

    private static short sampleAsShort(Buffer buffer, int relativeIndex) {
        int index = buffer.position() + relativeIndex;
        if (buffer instanceof ShortBuffer) {
            return ((ShortBuffer) buffer).get(index);
        }
        if (buffer instanceof FloatBuffer) {
            return normalizedToShort(((FloatBuffer) buffer).get(index));
        }
        if (buffer instanceof DoubleBuffer) {
            return normalizedToShort(((DoubleBuffer) buffer).get(index));
        }
        if (buffer instanceof IntBuffer) {
            return (short) (((IntBuffer) buffer).get(index) >> 16);
        }
        if (buffer instanceof ByteBuffer) {
            return (short) (((ByteBuffer) buffer).get(index) << 8);
        }
        throw new IllegalArgumentException("未识别的音频采样类型："
                + buffer.getClass().getName());
    }

    private static short normalizedToShort(double sample) {
        double clipped = Math.max(-1.0, Math.min(1.0, sample));
        return (short) Math.round(clipped * 32767.0);
    }

    private void closeAudioLine() {
        SourceDataLine line = audioLine;
        audioLine = null;
        if (line != null && line.isOpen()) {
            try {
                line.stop();
            } finally {
                line.close();
            }
        }
    }

    private void safeNotify(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException listenerFailure) {
            System.err.println("[JavaCV] 播放监听器异常："
                    + listenerFailure.getMessage());
        }
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    /**
     * Copies a converter-owned frame before it is queued to another thread.
     */
    public static BufferedImage copyFrame(BufferedImage image) {
        return Java2DFrameConverter.cloneBufferedImage(image);
    }

    public interface Listener {
        void onReady(MediaInfo info);

        /**
         * Receives an image owned by the decoder. Consumers that retain it
         * after this callback returns must first call
         * {@link JavaCvMediaPlayer#copyFrame(BufferedImage)}.
         */
        void onFrame(BufferedImage image, long timestampMicros);

        void onProgress(long currentMicros, long durationMicros);

        void onPlaying();

        void onPaused();

        void onFinished();

        void onError(Throwable failure);
    }

    public abstract static class ListenerAdapter implements Listener {
        @Override
        public void onReady(MediaInfo info) {
        }

        @Override
        public void onFrame(BufferedImage image, long timestampMicros) {
        }

        @Override
        public void onProgress(long currentMicros, long durationMicros) {
        }

        @Override
        public void onPlaying() {
        }

        @Override
        public void onPaused() {
        }

        @Override
        public void onFinished() {
        }

        @Override
        public void onError(Throwable failure) {
        }
    }

    public static final class MediaInfo {
        private final String container;
        private final String videoCodec;
        private final String audioCodec;
        private final int width;
        private final int height;
        private final double frameRate;
        private final int sampleRate;
        private final int audioChannels;
        private final long durationMicros;
        private final boolean audioOutputAvailable;

        MediaInfo(String container,
                  String videoCodec,
                  String audioCodec,
                  int width,
                  int height,
                  double frameRate,
                  int sampleRate,
                  int audioChannels,
                  long durationMicros,
                  boolean audioOutputAvailable) {
            this.container = container;
            this.videoCodec = videoCodec;
            this.audioCodec = audioCodec;
            this.width = width;
            this.height = height;
            this.frameRate = frameRate;
            this.sampleRate = sampleRate;
            this.audioChannels = audioChannels;
            this.durationMicros = durationMicros;
            this.audioOutputAvailable = audioOutputAvailable;
        }

        public String getContainer() {
            return container;
        }

        public String getVideoCodec() {
            return videoCodec;
        }

        public String getAudioCodec() {
            return audioCodec;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public double getFrameRate() {
            return frameRate;
        }

        public int getSampleRate() {
            return sampleRate;
        }

        public int getAudioChannels() {
            return audioChannels;
        }

        public long getDurationMicros() {
            return durationMicros;
        }

        public boolean isAudioOutputAvailable() {
            return audioOutputAvailable;
        }

        public String describe() {
            return String.format(Locale.ROOT,
                    "container=%s, video=%s, audio=%s, size=%dx%d, "
                            + "fps=%.3f, sampleRate=%d, channels=%d, "
                            + "duration=%.3fs, audioOutput=%s",
                    container, videoCodec, audioCodec, width, height,
                    frameRate, sampleRate, audioChannels,
                    durationMicros / 1_000_000.0,
                    audioOutputAvailable ? "ready" : "missing");
        }
    }
}
