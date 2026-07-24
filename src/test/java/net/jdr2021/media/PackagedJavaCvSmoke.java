package net.jdr2021.media;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line smoke test intentionally free of JUnit dependencies. The build
 * audit runs it with only the shaded application JAR and test classes on the
 * classpath, proving that the selected native FFmpeg files are self-contained.
 */
public final class PackagedJavaCvSmoke {
    private PackagedJavaCvSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path wave = Files.createTempFile("oss-javacv-packaged-", ".wav");
        Path video = Files.createTempFile("oss-javacv-packaged-", ".mp4");
        try {
            Files.write(wave, waveFixture());
            probeAudio(wave);
            createVideo(video);
            probeVideo(video.toAbsolutePath().toString(), "local");
            if (args.length > 0 && !args[0].trim().isEmpty()) {
                probeVideo(args[0].trim(), "remote");
            }
        } finally {
            Files.deleteIfExists(wave);
            Files.deleteIfExists(video);
        }
    }

    private static void probeAudio(Path wave) throws Exception {
        FFmpegFrameGrabber grabber =
                new FFmpegFrameGrabber(wave.toFile());
        try {
            grabber.start();
            Frame frame = grabber.grabSamples();
            if (grabber.getAudioChannels() != 1
                    || grabber.getSampleRate() != 8_000
                    || frame == null || frame.samples == null) {
                throw new IllegalStateException(
                        "packaged FFmpeg audio probe failed");
            }
            System.out.println("[JavaCV smoke] bundled FFmpeg audio ready; "
                    + "format=" + grabber.getFormat()
                    + "; audio=" + grabber.getAudioCodecName());
        } finally {
            try {
                grabber.stop();
            } finally {
                grabber.release();
            }
        }
    }

    private static void createVideo(Path video) throws Exception {
        FFmpegFrameRecorder recorder =
                new FFmpegFrameRecorder(video.toFile(), 32, 24);
        Java2DFrameConverter converter = new Java2DFrameConverter();
        recorder.setFormat("mp4");
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
        recorder.setFrameRate(5.0);
        try {
            recorder.start();
            BufferedImage image = new BufferedImage(
                    32, 24, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(Color.GREEN);
                graphics.fillRect(0, 0, 32, 24);
            } finally {
                graphics.dispose();
            }
            recorder.record(converter.convert(image));
            recorder.record(converter.convert(image));
            recorder.stop();
        } finally {
            converter.close();
            recorder.release();
        }
    }

    private static void probeVideo(String source, String label)
            throws Exception {
        FFmpegFrameGrabber grabber =
                new FFmpegFrameGrabber(source);
        grabber.setTimeout(15_000);
        grabber.setOption("rw_timeout", "15000000");
        try {
            grabber.start();
            Frame frame = grabber.grabImage();
            if (grabber.getImageWidth() <= 0
                    || grabber.getImageHeight() <= 0
                    || frame == null || frame.image == null) {
                throw new IllegalStateException(
                        "packaged FFmpeg " + label
                                + " video probe failed");
            }
            System.out.println("[JavaCV smoke] bundled FFmpeg "
                    + label + " video ready; "
                    + "format=" + grabber.getFormat()
                    + "; video=" + grabber.getVideoCodecName());
        } finally {
            try {
                grabber.stop();
            } finally {
                grabber.release();
            }
        }
    }

    private static byte[] waveFixture() {
        int sampleRate = 8_000;
        int samples = 160;
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
        for (int index = 0; index < samples; index++) {
            wave.putShort((short) (index * 8));
        }
        return wave.array();
    }
}
