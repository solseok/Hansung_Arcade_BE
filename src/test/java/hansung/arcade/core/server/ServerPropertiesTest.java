package hansung.arcade.core.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerPropertiesTest {

    @Test
    void 실행_인자가_기본값을_덮어쓴다() {
        ServerProperties properties = ServerProperties.of(
                Map.of("network.port", "9000", "app.title", "Hansung Arcade Server"),
                List.of("--network.port=9100", "무시되는인자", "--플래그만"));

        assertEquals(9100, properties.getInt("network.port", 0));
        assertEquals("Hansung Arcade Server", properties.get("app.title"));
    }

    @Test
    void 값이_없으면_기본값을_쓰고_필수값이면_실패한다() {
        ServerProperties properties = ServerProperties.of(Map.of(), List.of());

        assertEquals("기본", properties.get("app.title", "기본"));
        assertEquals(9000, properties.getInt("network.port", 9000));
        assertThrows(IllegalStateException.class, () -> properties.get("app.title"));
    }

    @Test
    void 숫자가_아닌_값을_숫자로_읽으면_실패한다() {
        ServerProperties properties = ServerProperties.of(Map.of("network.port", "abc"), List.of());

        assertThrows(IllegalStateException.class, () -> properties.getInt("network.port", 9000));
    }

    @Test
    void 리소스의_application_properties를_읽는다() {
        ServerProperties properties = ServerProperties.load(ServerPropertiesTest.class, List.of());

        // 클라이언트의 network.port 기본값과 같아야 접속된다
        assertEquals(9000, properties.getInt("network.port", 0));
    }
}
