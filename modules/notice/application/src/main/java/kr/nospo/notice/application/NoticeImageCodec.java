package kr.nospo.notice.application;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import kr.nospo.support.ApiException;

final class NoticeImageCodec {
    private static final int MAX_AXIS = 4096;
    private static final long MAX_PIXELS = 8L * 1024 * 1024;
    private static final int MAX_PNG_BYTES = 16 * 1024 * 1024 - 1;
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10
    };

    private NoticeImageCodec() {}

    static byte[] sanitize(byte[] input, String filename, String mediaType) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String format;
        if (matches(input, PNG_SIGNATURE) && name.endsWith(".png")
                && "image/png".equalsIgnoreCase(mediaType)) {
            validatePngChunks(input);
            format = "PNG";
        } else if (matches(input, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})
                && (name.endsWith(".jpg") || name.endsWith(".jpeg"))
                && "image/jpeg".equalsIgnoreCase(mediaType)
                && input[input.length - 2] == (byte) 0xff && input[input.length - 1] == (byte) 0xd9) {
            format = "JPEG";
        } else {
            throw invalidImage();
        }

        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            var reader = ImageIO.getImageReadersByFormatName(format).next();
            try {
                reader.addIIOReadWarningListener((source, warning) -> { throw invalidImage(); });
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_AXIS || height > MAX_AXIS
                        || (long) width * height > MAX_PIXELS) {
                    throw new ApiException(422, "INVALID_IMAGE_DIMENSIONS");
                }
                BufferedImage decoded = reader.read(0);
                var pixels = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                var graphics = pixels.createGraphics();
                try {
                    graphics.setComposite(java.awt.AlphaComposite.Src);
                    graphics.drawImage(decoded, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                return encodePng(pixels);
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw invalidImage();
        }
    }

    private static boolean matches(byte[] input, byte[] signature) {
        return input.length >= signature.length
                && Arrays.equals(input, 0, signature.length, signature, 0, signature.length);
    }

    private static void validatePngChunks(byte[] input) {
        var data = ByteBuffer.wrap(input);
        var crc = new CRC32();
        int offset = PNG_SIGNATURE.length;
        while (input.length - offset >= 12) {
            int length = data.getInt(offset);
            if (length < 0 || length > input.length - offset - 12) throw invalidImage();
            int type = data.getInt(offset + 4);
            switch (type) {
                case 0x6163544c, 0x6663544c, 0x66644154 -> // acTL, fcTL, fdAT
                        throw new ApiException(422, "ANIMATED_IMAGE_NOT_ALLOWED");
                default -> { }
            }
            crc.reset();
            crc.update(input, offset + 4, length + 4);
            if ((int) crc.getValue() != data.getInt(offset + 8 + length)) throw invalidImage();
            offset += length + 12;
            if (type == 0x49454e44) { // IEND
                if (length != 0 || offset != input.length) throw invalidImage();
                return;
            }
        }
        throw invalidImage();
    }

    private static byte[] encodePng(BufferedImage pixels) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("PNG").next();
        try (var output = new MemoryCacheImageOutputStream(bytes) {
            @Override
            public void write(int value) throws IOException {
                checkSize(1);
                super.write(value);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                checkSize(length);
                super.write(buffer, offset, length);
            }

            private void checkSize(int length) throws IOException {
                if (getStreamPosition() + length > MAX_PNG_BYTES)
                    throw new ApiException(413, "IMAGE_TOO_LARGE");
            }
        }) {
            writer.setOutput(output);
            writer.write(pixels);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static ApiException invalidImage() {
        return new ApiException(422, "INVALID_IMAGE");
    }
}
