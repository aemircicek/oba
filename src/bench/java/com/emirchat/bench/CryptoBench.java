package com.emirchat.bench;

import com.emirchat.call.VoiceCrypto;
import com.emirchat.crypto.Identity;
import com.emirchat.e2e.GroupKeys;
import com.emirchat.e2e.Messenger;
import com.emirchat.e2e.PrekeyBundle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** End-to-end encryption as the app does it: a direct message, a group message, a voice packet. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CryptoBench {
    private static final byte[] TEXT = new byte[200];
    private static final byte[] AUDIO = new byte[60];

    private Messenger emir;
    private Messenger efe;
    private Identity emirId;
    private Identity efeId;
    private GroupKeys groupEmir;
    private GroupKeys groupEfe;
    private VoiceCrypto.Sender voiceSender;
    private VoiceCrypto.Receiver voiceReceiver;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        Path dir = Files.createTempDirectory("oba-bench");
        emirId = Identity.generate();
        efeId = Identity.generate();
        emir = Messenger.open(dir.resolve("emir"), emirId);
        efe = Messenger.open(dir.resolve("efe"), efeId);
        List<Map.Entry<Integer, byte[]>> prekeys = new ArrayList<>(efe.newOneTimePrekeys(1));
        Messenger.PublicKeys k = efe.publicKeys();
        PrekeyBundle bundle = new PrekeyBundle(efeId.publicKeyBytes(), k.dhIdentity(), k.dhIdentitySignature(),
                k.signedPrekeyId(), k.signedPrekey(), k.signedPrekeySignature(), prekeys.get(0).getKey(),
                prekeys.get(0).getValue());
        byte[] first = emir.encrypt("efe", efeId.publicKeyBytes(), () -> bundle, TEXT);
        efe.decrypt("emir", emirId.publicKeyBytes(), first);
        efe.decrypt("emir", emirId.publicKeyBytes(), emir.encrypt("efe", efeId.publicKeyBytes(), () -> bundle, TEXT));

        groupEmir = new GroupKeys(dir.resolve("g-emir"));
        groupEfe = new GroupKeys(dir.resolve("g-efe"));
        groupEfe.acceptDistribution(7, "emir", groupEmir.distribution(7));

        VoiceCrypto.Key key = VoiceCrypto.newKey();
        voiceSender = new VoiceCrypto.Sender(key);
        voiceReceiver = new VoiceCrypto.Receiver();
        voiceReceiver.accept("emir", key);
    }

    /** One direct message: Double Ratchet step, AES-GCM, signature, and the session saved on both sides. */
    @Benchmark
    public byte[] directMessage() throws Exception {
        byte[] sealed = emir.encrypt("efe", efeId.publicKeyBytes(), () -> {
            throw new IllegalStateException("session exists");
        }, TEXT);
        return efe.decrypt("emir", emirId.publicKeyBytes(), sealed);
    }

    /** One group message with sender keys (chain step, AES-GCM, Ed25519 signature). */
    @Benchmark
    public byte[] groupMessage() throws Exception {
        return groupEfe.decrypt(7, "emir", groupEmir.encrypt(7, TEXT));
    }

    /** One 20 ms voice packet sealed and opened. */
    @Benchmark
    public byte[] voicePacket() {
        return voiceReceiver.open(7, "emir", voiceSender.seal(7, "emir", AUDIO));
    }
}
