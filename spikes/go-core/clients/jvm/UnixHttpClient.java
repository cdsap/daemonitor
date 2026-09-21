import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Spike JVM client: HTTP/1.0 GET over a Unix domain socket with zero third-party deps.
 *
 * Compile + run (JDK 16+):
 *   javac clients/jvm/UnixHttpClient.java
 *   java -cp clients/jvm UnixHttpClient $TMPDIR/daemonitor-core.sock /v1/health
 *   java -cp clients/jvm UnixHttpClient $TMPDIR/daemonitor-core.sock /v1/processes
 *
 * This is the seam a future Kotlin CLI/desktop would use (or OkHttp with a Unix dialer).
 */
public final class UnixHttpClient {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: UnixHttpClient <socket-path> [path]");
            System.exit(2);
        }
        Path socket = Path.of(args[0]);
        String path = args.length > 1 ? args[1] : "/v1/health";

        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socket);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            String request = "GET " + path + " HTTP/1.0\r\nHost: localhost\r\nAccept: application/json\r\n\r\n";
            channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII)));

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            ByteBuffer buf = ByteBuffer.allocate(8192);
            while (channel.read(buf) > 0) {
                buf.flip();
                byte[] chunk = new byte[buf.remaining()];
                buf.get(chunk);
                body.write(chunk);
                buf.clear();
            }
            String raw = body.toString(StandardCharsets.UTF_8);
            int split = raw.indexOf("\r\n\r\n");
            if (split < 0) {
                System.out.print(raw);
                return;
            }
            System.out.print(raw.substring(split + 4));
        }
    }

    private UnixHttpClient() {}
}
