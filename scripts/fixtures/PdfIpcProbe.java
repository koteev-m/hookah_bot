import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

// Test-only protocol injector. It does not depend on or invoke any PDF parser.
public final class PdfIpcProbe {
    public static void main(String[] args) throws Exception {
        byte[] magic = new byte[] {72, 84, 80, 68, 70, 1};
        byte[] frame = ByteBuffer.allocate(11).put(magic).put((byte) 0).putInt(0).array();
        switch (args[0]) {
            case "short-header": frame = new byte[] {72, 84}; break;
            case "bad-magic": frame[0] = 0; break;
            case "unknown-op": frame[6] = 9; break;
            case "oversize": ByteBuffer.wrap(frame, 7, 4).putInt(Integer.MAX_VALUE); break;
            case "partial-body": frame[6] = 1; ByteBuffer.wrap(frame, 7, 4).putInt(100); break;
            case "trailing-byte": frame = java.util.Arrays.copyOf(frame, 12); break;
            case "slow-header": frame = new byte[] {72}; break;
            default: throw new IllegalArgumentException();
        }
        try (SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            socket.connect(UnixDomainSocketAddress.of(Path.of("/run/hookah-pdf/worker.sock")));
            socket.write(ByteBuffer.wrap(frame));
            if (args[0].equals("slow-header")) Thread.sleep(2500); else socket.shutdownOutput();
            ByteBuffer response = ByteBuffer.allocate(8);
            try {
                int n = socket.read(response);
                if (n > 0) throw new AssertionError("Malformed IPC was admitted");
            } catch (java.net.SocketException expected) { }
        }
        System.out.println("IPC_REJECTED");
    }
}
