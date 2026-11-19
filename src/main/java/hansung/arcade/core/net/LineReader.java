package hansung.arcade.core.net;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * \n으로 끝나는 UTF-8 줄을 하나씩 읽는다. {@code BufferedReader.readLine()}과 달리 줄 길이에 한도가 있어서,
 * 끝없이 긴 줄을 보내는 상대 때문에 메모리가 차지 않는다.
 *
 * <p>규칙은 LF 하나지만, 줄 끝의 \r 하나는 너그럽게 떼고 읽는다 (Windows의 {@code println}).</p>
 */
public final class LineReader {

    private final InputStream in;
    private final int maxBytes;
    private byte[] buffer = new byte[256];

    public LineReader(InputStream in, int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("줄 최대 길이는 1 이상이어야 합니다: " + maxBytes);
        }
        this.in = in instanceof BufferedInputStream ? in : new BufferedInputStream(in);
        this.maxBytes = maxBytes;
    }

    /**
     * 다음 한 줄 (줄 끝 \n 제외).
     *
     * @return 상대가 연결을 닫았으면 null. 끝에 \n 없이 남은 조각은 버린다
     * @throws LineTooLongException \n이 나오기 전에 maxBytes를 넘었을 때. 이후 같은 연결로는 더 읽지 않는다
     */
    public String readLine() throws IOException {
        int length = 0;
        while (true) {
            int b = in.read();
            if (b == -1) {
                return null;
            }
            if (b == '\n') {
                if (length > 0 && buffer[length - 1] == '\r') {
                    length--;
                }
                return new String(buffer, 0, length, StandardCharsets.UTF_8);
            }
            // \r\n의 \r은 한도에 세지 않도록 한 칸 여유를 둔다
            if (length >= maxBytes + 1 || (length == maxBytes && b != '\r')) {
                throw new LineTooLongException(maxBytes);
            }
            if (length == buffer.length) {
                buffer = Arrays.copyOf(buffer, Math.min(buffer.length * 2, maxBytes + 1));
            }
            buffer[length++] = (byte) b;
        }
    }

    /** 한 줄이 한도를 넘었다 */
    public static final class LineTooLongException extends IOException {

        public LineTooLongException(int maxBytes) {
            super("한 줄이 %d바이트를 넘었습니다.".formatted(maxBytes));
        }
    }
}
