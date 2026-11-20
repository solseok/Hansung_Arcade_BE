package hansung.arcade.domain.player.exception;

/**
 * 이미 다른 플레이어가 쓰고 있는 닉네임으로 등록하려 했다.
 */
public class DuplicateNicknameException extends RuntimeException {

    private final String nickname;

    public DuplicateNicknameException(String nickname) {
        super("이미 쓰고 있는 닉네임: " + nickname);
        this.nickname = nickname;
    }

    /** 겹친 닉네임 ({@code NicknameRule}로 다듬은 값) */
    public String nickname() {
        return nickname;
    }
}
