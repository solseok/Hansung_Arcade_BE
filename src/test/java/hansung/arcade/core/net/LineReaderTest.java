package hansung.arcade.core.net;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LineReaderTest {

    private static LineReader reader(String text, int maxBytes) {
        return new LineReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), maxBytes);
    }

    @Test
    void LF로_나눈_줄을_차례로_읽고_끝나면_null() throws IOException {
        LineReader reader = reader("{\"a\":1}\n\n한글 줄\n", 100);

        assertEquals("{\"a\":1}", reader.readLine());
        assertEquals("", reader.readLine());
        assertEquals("한글 줄", reader.readLine());
        assertNull(reader.readLine());
    }

    @Test
    void 줄_끝의_CR_하나는_뗀다() throws IOException {
        LineReader reader = reader("PING\r\n", 100);

        assertEquals("PING", reader.readLine());
    }

    @Test
    void LF_없이_끝난_조각은_버린다() throws IOException {
        LineReader reader = reader("완성\n미완", 100);

        assertEquals("완성", reader.readLine());
        assertNull(reader.readLine());
    }

    @Test
    void 한도와_같은_길이는_읽고_넘으면_실패한다() throws IOException {
        // 한글 한 글자는 UTF-8 3바이트
        assertEquals("가나", reader("가나\n", 6).readLine());
        assertEquals("가나", reader("가나\r\n", 6).readLine());
        assertThrows(LineReader.LineTooLongException.class, () -> reader("가나다\n", 6).readLine());
        assertThrows(LineReader.LineTooLongException.class, () -> reader("1234567", 6).readLine());
    }

    @Test
    void 공통_규칙의_32KiB까지_읽는다() throws IOException {
        String max = "a".repeat(Protocol.MAX_LINE_BYTES);

        assertEquals(max, reader(max + "\n", Protocol.MAX_LINE_BYTES).readLine());
        assertThrows(LineReader.LineTooLongException.class,
                () -> reader(max + "a\n", Protocol.MAX_LINE_BYTES).readLine());
    }
}
