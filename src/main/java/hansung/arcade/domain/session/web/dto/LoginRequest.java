package hansung.arcade.domain.session.web.dto;

import java.util.List;

/**
 * LOGIN 요청 body. {@code {"nicknames": ["상상부기", "꾸꾸"]}}
 *
 * @param nicknames 1P면 1개, 2P면 2개. 순서대로 1P, 2P. 빠지면 null
 */
public record LoginRequest(List<String> nicknames) {
}
