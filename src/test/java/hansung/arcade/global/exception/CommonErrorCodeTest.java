package hansung.arcade.global.exception;

import hansung.arcade.core.net.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommonErrorCodeTest {

    @Test
    void 공통_code는_네트워크_계층의_ErrorCode와_같다() {
        assertEquals(ErrorCode.BAD_MESSAGE.name(), CommonErrorCode.BAD_MESSAGE);
        assertEquals(ErrorCode.NOT_LOGGED_IN.name(), CommonErrorCode.NOT_LOGGED_IN);
        assertEquals(ErrorCode.WRONG_STATE.name(), CommonErrorCode.WRONG_STATE);
    }
}
