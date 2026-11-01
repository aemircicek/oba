package com.emirchat.protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;

/** Length-prefixed binary frames with a hard size limit, so a peer cannot make us allocate unbounded memory. */
public final class FrameIO {
    public static final int MAX_FRAME_SIZE = 64 * 1024;

    private final DataInputStream in;
    private final DataOutputStream out;

    public FrameIO(InputStream in, OutputStream out) {
        this.in = new DataInputStream(new BufferedInputStream(in));
        this.out = new DataOutputStream(new BufferedOutputStream(out));
    }

    public synchronized void write(byte[] payload) throws IOException {
        if (payload.length > MAX_FRAME_SIZE) {
            throw new ProtocolException("Outgoing frame too large: " + payload.length);
        }
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    public byte[] read() throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_FRAME_SIZE) {
            throw new ProtocolException("Incoming frame has invalid length: " + length);
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        return payload;
    }
}
