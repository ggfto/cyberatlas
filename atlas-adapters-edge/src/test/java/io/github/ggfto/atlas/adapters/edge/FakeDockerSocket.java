package io.github.ggfto.atlas.adapters.edge;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * docker.sock falso: um servidor HTTP/1.1 num socket Unix de verdade, que registra todo caminho pedido. Qualquer
 * caminho fora do mapa responde 500 e fica registrado, para o teste provar que nunca foi pedido.
 */
final class FakeDockerSocket implements AutoCloseable {

    record Reply(int status, String body, boolean chunked) {
    }

    final Path socket;
    final List<String> requested = new CopyOnWriteArrayList<>();
    private final Map<String, Reply> replies;
    private final ServerSocketChannel server;
    private final Thread loop;

    FakeDockerSocket(Path dir, Map<String, Reply> replies) throws IOException {
        this.socket = dir.resolve("docker.sock");
        this.replies = replies;
        this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        this.loop = Thread.ofVirtual().start(this::serve);
    }

    private void serve() {
        while (server.isOpen()) {
            try (SocketChannel c = server.accept()) {
                String request = readHead(c);
                String path = request.split(" ")[1];
                requested.add(path);
                Reply r = replies.getOrDefault(path, new Reply(500, "{\"message\":\"nao devia ser pedido\"}", false));
                c.write(ByteBuffer.wrap(response(r)));
            } catch (IOException e) {
                return;
            }
        }
    }

    private static String readHead(SocketChannel c) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(8192);
        StringBuilder sb = new StringBuilder();
        while (!sb.toString().contains("\r\n\r\n") && c.read(buf) > 0) {
            buf.flip();
            sb.append(StandardCharsets.UTF_8.decode(buf));
            buf.clear();
        }
        return sb.toString();
    }

    private static byte[] response(Reply r) {
        String head = "HTTP/1.1 " + r.status() + " X\r\nContent-Type: application/json\r\n";
        if (!r.chunked()) {
            byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
            return (head + "Content-Length: " + b.length + "\r\n\r\n" + r.body()).getBytes(StandardCharsets.UTF_8);
        }
        StringBuilder sb = new StringBuilder(head + "Transfer-Encoding: chunked\r\n\r\n");
        byte[] all = r.body().getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.writeBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < all.length; i += 100) {
            int n = Math.min(100, all.length - i);
            out.writeBytes((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(all, i, n);
            out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.writeBytes("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    @Override
    public void close() throws IOException {
        server.close();
        loop.interrupt();
    }
}
