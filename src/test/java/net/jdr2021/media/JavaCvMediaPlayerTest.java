package net.jdr2021.media;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import net.jdr2021.utils.RuntimePlatform;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

public class JavaCvMediaPlayerTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void convertsInterleavedAndPlanarSamplesToLittleEndianPcm() {
        byte[] interleaved = JavaCvMediaPlayer.toPcm16(
                new ShortBuffer[]{ShortBuffer.wrap(
                        new short[]{0x1234, (short) 0xfedc})}, 2);
        assertArrayEquals(new byte[]{
                0x34, 0x12, (byte) 0xdc, (byte) 0xfe
        }, interleaved);

        byte[] planar = JavaCvMediaPlayer.toPcm16(
                new ShortBuffer[]{
                        ShortBuffer.wrap(new short[]{1, 2}),
                        ShortBuffer.wrap(new short[]{10, 20})
                }, 2);
        assertArrayEquals(new byte[]{
                1, 0, 10, 0, 2, 0, 20, 0
        }, planar);

        byte[] floating = JavaCvMediaPlayer.toPcm16(
                new FloatBuffer[]{FloatBuffer.wrap(
                        new float[]{-1.0f, 0.0f, 1.0f})}, 1);
        assertArrayEquals(new byte[]{
                1, (byte) 0x80, 0, 0, (byte) 0xff, 0x7f
        }, floating);
    }

    @Test
    public void initializesBundledFfmpegAgainstLocalWaveFile()
            throws Exception {
        requireCurrentNativeClassifier();
        Path wave = temporary.newFile("probe.wav").toPath();
        Files.write(wave, createWaveFixture());

        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<JavaCvMediaPlayer.MediaInfo> mediaInfo =
                new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        JavaCvMediaPlayer player = new JavaCvMediaPlayer(
                wave.toAbsolutePath().toString(),
                new JavaCvMediaPlayer.ListenerAdapter() {
                    @Override
                    public void onReady(
                            JavaCvMediaPlayer.MediaInfo info) {
                        mediaInfo.set(info);
                        ready.countDown();
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        failure.set(throwable);
                        failed.countDown();
                    }
                });
        try {
            player.start();
            assertTrue("JavaCV native initialization timed out",
                    ready.await(30, TimeUnit.SECONDS)
                            || failed.await(100, TimeUnit.MILLISECONDS));
            if (failure.get() != null) {
                throw new AssertionError(
                        "JavaCV native initialization failed",
                        failure.get());
            }
            JavaCvMediaPlayer.MediaInfo info = mediaInfo.get();
            assertNotNull(info);
            assertEquals(1, info.getAudioChannels());
            assertEquals(8_000, info.getSampleRate());
            assertTrue(info.getDurationMicros() > 0L);
            assertTrue(info.getContainer().contains("wav"));
        } finally {
            player.close();
        }
    }

    @Test
    public void decodesVideoFramesOnBackgroundPlayerThread()
            throws Exception {
        requireCurrentNativeClassifier();
        Path video = temporary.newFile("video.mp4").toPath();
        createVideoFixture(video);

        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch frameDecoded = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<JavaCvMediaPlayer> playerReference =
                new AtomicReference<>();
        JavaCvMediaPlayer player = new JavaCvMediaPlayer(
                video.toAbsolutePath().toString(),
                new JavaCvMediaPlayer.ListenerAdapter() {
                    @Override
                    public void onReady(
                            JavaCvMediaPlayer.MediaInfo info) {
                        assertEquals(64, info.getWidth());
                        assertEquals(48, info.getHeight());
                        ready.countDown();
                        playerReference.get().play();
                    }

                    @Override
                    public void onFrame(BufferedImage image,
                                        long timestampMicros) {
                        if (image != null && image.getWidth() == 64
                                && image.getHeight() == 48) {
                            frameDecoded.countDown();
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        failure.set(throwable);
                        ready.countDown();
                        frameDecoded.countDown();
                    }
                });
        playerReference.set(player);
        try {
            player.start();
            assertTrue("JavaCV video initialization timed out",
                    ready.await(30, TimeUnit.SECONDS));
            assertTrue("JavaCV video frame decode timed out",
                    frameDecoded.await(10, TimeUnit.SECONDS));
            if (failure.get() != null) {
                throw new AssertionError(
                        "JavaCV video decode failed", failure.get());
            }
        } finally {
            player.close();
        }
    }

    @Test
    public void streamsVideoFromHttpRangeSource() throws Exception {
        requireCurrentNativeClassifier();
        Path video = temporary.newFile("remote-video.mp4").toPath();
        createVideoFixture(video);
        byte[] videoBytes = Files.readAllBytes(video);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/remote-video.mp4", exchange ->
                serveRange(exchange, videoBytes, requests));
        server.start();

        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch frameDecoded = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<JavaCvMediaPlayer> playerReference =
                new AtomicReference<>();
        JavaCvMediaPlayer player = new JavaCvMediaPlayer(
                "http://127.0.0.1:" + server.getAddress().getPort()
                        + "/remote-video.mp4",
                new JavaCvMediaPlayer.ListenerAdapter() {
                    @Override
                    public void onReady(
                            JavaCvMediaPlayer.MediaInfo info) {
                        ready.countDown();
                        playerReference.get().play();
                    }

                    @Override
                    public void onFrame(BufferedImage image,
                                        long timestampMicros) {
                        frameDecoded.countDown();
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        failure.set(throwable);
                        ready.countDown();
                        frameDecoded.countDown();
                    }
                });
        playerReference.set(player);
        try {
            player.start();
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            assertTrue(frameDecoded.await(10, TimeUnit.SECONDS));
            if (failure.get() != null) {
                throw new AssertionError(
                        "JavaCV HTTP range playback failed",
                        failure.get());
            }
            assertTrue(requests.get() > 0);
        } finally {
            player.close();
            server.stop(0);
        }
    }

    private static byte[] createWaveFixture() {
        int sampleRate = 8_000;
        int samples = 800;
        int dataBytes = samples * 2;
        ByteBuffer wave = ByteBuffer.allocate(44 + dataBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        wave.put(new byte[]{'R', 'I', 'F', 'F'});
        wave.putInt(36 + dataBytes);
        wave.put(new byte[]{'W', 'A', 'V', 'E'});
        wave.put(new byte[]{'f', 'm', 't', ' '});
        wave.putInt(16);
        wave.putShort((short) 1);
        wave.putShort((short) 1);
        wave.putInt(sampleRate);
        wave.putInt(sampleRate * 2);
        wave.putShort((short) 2);
        wave.putShort((short) 16);
        wave.put(new byte[]{'d', 'a', 't', 'a'});
        wave.putInt(dataBytes);
        for (int i = 0; i < samples; i++) {
            wave.putShort((short) (Math.sin(
                    2.0 * Math.PI * 440.0 * i / sampleRate) * 4096));
        }
        return wave.array();
    }

    private static void requireCurrentNativeClassifier() {
        assumeTrue("Native media tests run on their matching release platform",
                RuntimePlatform.isNativeMediaCompatible());
    }

    private static void createVideoFixture(Path output) throws Exception {
        FFmpegFrameRecorder recorder =
                new FFmpegFrameRecorder(output.toFile(), 64, 48);
        Java2DFrameConverter converter = new Java2DFrameConverter();
        recorder.setFormat("mp4");
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
        recorder.setFrameRate(10.0);
        recorder.setVideoBitrate(180_000);
        try {
            recorder.start();
            for (int index = 0; index < 6; index++) {
                BufferedImage image = new BufferedImage(
                        64, 48, BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D graphics = image.createGraphics();
                try {
                    graphics.setColor(index % 2 == 0
                            ? Color.BLUE : Color.ORANGE);
                    graphics.fillRect(0, 0, 64, 48);
                } finally {
                    graphics.dispose();
                }
                recorder.record(converter.convert(image));
            }
            recorder.stop();
        } finally {
            converter.close();
            recorder.release();
        }
    }

    private static void serveRange(HttpExchange exchange,
                                   byte[] bytes,
                                   AtomicInteger requests)
            throws IOException {
        requests.incrementAndGet();
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        exchange.getResponseHeaders().set("Content-Type", "video/mp4");
        exchange.getResponseHeaders().set(
                "Content-Length", String.valueOf(bytes.length));
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        String range = exchange.getRequestHeaders().getFirst("Range");
        int start = 0;
        int end = bytes.length - 1;
        int status = 200;
        if (range != null && range.startsWith("bytes=")) {
            String[] bounds = range.substring(6).split("-", 2);
            start = Integer.parseInt(bounds[0]);
            if (bounds.length > 1 && !bounds[1].isEmpty()) {
                end = Math.min(end, Integer.parseInt(bounds[1]));
            }
            status = 206;
            exchange.getResponseHeaders().set(
                    "Content-Range", "bytes " + start + "-"
                            + end + "/" + bytes.length);
        }
        int length = end - start + 1;
        exchange.getResponseHeaders().set(
                "Content-Length", String.valueOf(length));
        exchange.sendResponseHeaders(status, length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes, start, length);
        }
    }
}
