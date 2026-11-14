package kr.nospo.member.integration;

import kr.nospo.member.application.ProfileImageCodec;
import kr.nospo.support.ApiException;

import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

@Component
public class ImageIoProfileCodec implements ProfileImageCodec {
    public byte[] sanitize(byte[] input) {
        if (input.length == 0 || input.length > 5 * 1024 * 1024)
            throw new ApiException(413, "IMAGE_TOO_LARGE");
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new ApiException(422, "INVALID_IMAGE");
            var reader = readers.next();
            try {
                if (!Set.of("JPEG", "PNG", "WEBP")
                        .contains(reader.getFormatName().toUpperCase(Locale.ROOT)))
                    throw new ApiException(422, "INVALID_IMAGE");
                // PNG animation control and WebP animation chunks are rejected before decode.
                rejectAnimation(input, reader.getFormatName());
                reader.setInput(stream);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096)
                    throw new ApiException(422, "INVALID_IMAGE_DIMENSIONS");
                var decoded = reader.read(0);
                var clean = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                var graphics = clean.createGraphics();
                try {
                    graphics.drawImage(decoded, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
                var output = new ByteArrayOutputStream();
                ImageIO.write(clean, "png", output);
                if (output.size() > 16 * 1024 * 1024 - 1)
                    throw new ApiException(413, "IMAGE_TOO_LARGE");
                return output.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException ex) {
            throw new ApiException(422, "INVALID_IMAGE");
        }
    }

    private void rejectAnimation(byte[] data, String format) {
        boolean png = format.equalsIgnoreCase("png"), webp = format.equalsIgnoreCase("webp");
        if (!png && !webp) return;
        int position = png ? 8 : 12;
        while (position + 8 <= data.length) {
            var buffer =
                    java.nio.ByteBuffer.wrap(data, position, 4)
                            .order(
                                    png
                                            ? java.nio.ByteOrder.BIG_ENDIAN
                                            : java.nio.ByteOrder.LITTLE_ENDIAN);
            long size =
                    png
                            ? Integer.toUnsignedLong(buffer.getInt())
                            : Integer.toUnsignedLong(
                                    java.nio.ByteBuffer.wrap(data, position + 4, 4)
                                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                            .getInt());
            String type = new String(data, position + (png ? 4 : 0), 4, StandardCharsets.US_ASCII);
            if (type.equals("acTL") || type.equals("ANIM") || type.equals("ANMF"))
                throw new ApiException(422, "ANIMATED_IMAGE_NOT_ALLOWED");
            long next = position + 8L + size + (png ? 4 : (size & 1));
            if (next > data.length || next <= position)
                throw new ApiException(422, "INVALID_IMAGE");
            position = (int) next;
        }
    }
}
