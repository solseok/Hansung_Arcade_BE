package hansung.arcade.core.net;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 서버 전체가 함께 쓰는 JSON 변환기. {@link ObjectMapper}는 설정을 마친 뒤에는 여러 스레드가 같이 써도 안전하다.
 *
 * <p>줄바꿈 없는 한 줄로 쓰고(들여쓰기 끔), 문자열 속 줄바꿈은 {@code \n}으로 이스케이프된다.
 * 모르는 필드는 무시한다 (필드를 나중에 더해도 옛 코드가 깨지지 않게).</p>
 */
public final class Json {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {
    }

    /** 내용 없는 body: {@code {}} */
    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }
}
