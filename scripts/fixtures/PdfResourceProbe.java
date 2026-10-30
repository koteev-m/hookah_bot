import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

// Trusted test fixture only. Never parses PDFs; each mode runs in its own bounded container.
public final class PdfResourceProbe {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "memory" -> {
                var keep = new ArrayList<byte[]>();
                for (int n = 0; n < 100; n++) {
                    byte[] block = new byte[8 * 1024 * 1024];
                    for (int i = 0; i < block.length; i += 4096) block[i] = 1;
                    keep.add(block);
                }
                throw new AssertionError("Memory cgroup did not stop allocation");
            }
            case "pids" -> {
                var stop = new CountDownLatch(1);
                var threads = new ArrayList<Thread>();
                boolean refused = false;
                try {
                    for (int n = 0; n < 100; n++) {
                        var thread = new Thread(() -> {
                            try { stop.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        });
                        thread.start(); threads.add(thread);
                    }
                } catch (OutOfMemoryError expectedNativeThreadLimit) {
                    refused = true;
                } finally {
                    stop.countDown();
                    for (var thread : threads) thread.join(1000);
                }
                if (!refused || !Files.readString(Path.of("/sys/fs/cgroup/pids.events")).matches("(?s).*max [1-9][0-9]*.*")) {
                    throw new AssertionError("PIDs cgroup did not refuse thread creation");
                }
                System.out.println("PIDS_ENFORCED");
            }
            case "cpu" -> {
                long end = System.nanoTime() + 2_000_000_000L;
                Runnable busy = () -> { while (System.nanoTime() < end) Thread.onSpinWait(); };
                var second = new Thread(busy); second.start(); busy.run(); second.join();
                System.out.print(Files.readString(Path.of("/sys/fs/cgroup/cpu.stat")));
            }
            default -> throw new IllegalArgumentException();
        }
    }
}
