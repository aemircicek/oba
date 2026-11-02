package com.emirchat.chat;

import com.emirchat.e2e.Attachments;
import com.emirchat.protocol.Wire;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * A file shared in a conversation, carried inside an end-to-end encrypted message: where the
 * encrypted blob is on the server and everything needed to check and decrypt it. The server only
 * ever sees the blob, never this description.
 *
 * <p>The name and type come from the sender and are untrusted: {@link #safeName} reduces the name
 * to a plain file name (no directories, control characters or bidi overrides), and the type is
 * only used to decide whether to show an image preview.
 */
public record FileMessage(byte[] attachmentId, byte[] key, byte[] digest, long size, String name, String mimeType,
                          long sentAtMillis) {
    public static final int MAX_NAME_BYTES = 255;
    public static final int MAX_MIME_BYTES = 100;
    private static final int VERSION = 1;

    public FileMessage {
        if (attachmentId.length != Attachments.ID_LENGTH || key.length != Attachments.KEY_LENGTH
                || digest.length != Attachments.DIGEST_LENGTH) {
            throw new IllegalArgumentException("Malformed attachment reference");
        }
        if (size < 0 || size > Attachments.MAX_FILE_BYTES) throw new IllegalArgumentException("Invalid file size");
        attachmentId = attachmentId.clone();
        key = key.clone();
        digest = digest.clone();
        name = safeName(name);
        mimeType = mimeType == null ? "" : truncate(mimeType.toLowerCase(Locale.ROOT).strip(), MAX_MIME_BYTES);
    }

    @Override public byte[] attachmentId() { return attachmentId.clone(); }

    @Override public byte[] key() { return key.clone(); }

    @Override public byte[] digest() { return digest.clone(); }

    /** Size of the encrypted blob on the server. */
    public long ciphertextSize() {
        return size + Attachments.OVERHEAD;
    }

    /** True for common image formats small enough to preview inline. */
    public boolean isPreviewableImage() {
        String lower = name.toLowerCase(Locale.ROOT);
        boolean extension = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".bmp");
        return extension && mimeType.startsWith("image/") && size <= 10L * 1024 * 1024;
    }

    public byte[] encode() {
        return new Wire.Writer().u8(VERSION).bytes(attachmentId).bytes(key).bytes(digest).i64(size)
                .string(name).string(mimeType).i64(sentAtMillis).toByteArray();
    }

    public static FileMessage decode(byte[] data) throws ProtocolException {
        Wire.Reader r = new Wire.Reader(data);
        if (r.u8() != VERSION) throw new ProtocolException("Unknown file message version");
        byte[] id = r.exactBytes(Attachments.ID_LENGTH);
        byte[] key = r.exactBytes(Attachments.KEY_LENGTH);
        byte[] digest = r.exactBytes(Attachments.DIGEST_LENGTH);
        long size = r.i64();
        String name = r.string(MAX_NAME_BYTES);
        String mime = r.string(MAX_MIME_BYTES);
        long sentAt = r.i64();
        r.expectEnd();
        try {
            return new FileMessage(id, key, digest, size, name, mime, sentAt);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(e.getMessage());
        }
    }

    /**
     * Reduces an untrusted name to something safe to show and to save under: the last path
     * component, without control characters, bidi overrides or reserved characters, not starting
     * with a dot, at most {@value #MAX_NAME_BYTES} bytes.
     */
    public static String safeName(String raw) {
        String s = raw == null ? "" : raw;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        s = s.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> {
            boolean unsafe = Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT
                    || "<>:\"|?*".indexOf(cp) >= 0;
            sb.appendCodePoint(unsafe ? '_' : cp);
        });
        String cleaned = sb.toString().strip();
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        cleaned = truncate(cleaned, MAX_NAME_BYTES);
        return cleaned.isBlank() ? "file" : cleaned;
    }

    private static String truncate(String s, int maxBytes) {
        String out = s;
        while (out.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            out = out.substring(0, out.offsetByCodePoints(0, out.codePointCount(0, out.length()) - 1));
        }
        return out;
    }

    @Override public boolean equals(Object o) {
        return o instanceof FileMessage f && Arrays.equals(f.attachmentId, attachmentId) && Arrays.equals(f.key, key)
                && Arrays.equals(f.digest, digest) && f.size == size && f.name.equals(name)
                && f.mimeType.equals(mimeType) && f.sentAtMillis == sentAtMillis;
    }

    @Override public int hashCode() { return Arrays.hashCode(attachmentId); }

    @Override public String toString() { return "FileMessage[" + name + ", " + size + " bytes]"; } // never log keys
}
