package hansung.arcade.core.net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionTest {

    @Test
    void 송신_큐가_넘치면_send를_부른_스레드가_아닌_곳에서_끊김_리스너를_부른다() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             Socket accepted = listener.accept()) {
            CompletableFuture<Thread> closedOn = new CompletableFuture<>();
            ConnectionListener recorder = new ConnectionListener() {
                @Override
                public void onClose(Connection connection, String reason) {
                    closedOn.complete(Thread.currentThread());
                }
            };
            // start()를 부르지 않아 보내는 스레드가 없으므로 큐가 비지 않는다
            Connection connection = new Connection(1, accepted, new MessageDispatcher(), List.of(recorder));

            boolean lastAccepted = true;
            for (int i = 0; i <= Connection.MAX_QUEUED_MESSAGES; i++) {
                lastAccepted = connection.send(Message.push("PING", Json.object()));
            }

            assertFalse(lastAccepted);
            assertNotSame(Thread.currentThread(), closedOn.get(5, TimeUnit.SECONDS));
            assertTrue(connection.isClosed());
            assertFalse(connection.send(Message.push("PING", Json.object())));
        }
    }
}
