package hansung.arcade.domain.player.repository;

import hansung.arcade.domain.player.exception.DuplicateNicknameException;
import hansung.arcade.domain.player.model.Player;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 접속해 있는 플레이어 저장소. 닉네임 중복 검사와 playerId 발급을 맡는다.
 * 지금은 {@link InMemoryPlayerRepository} 하나지만, H2·SQLite로 바꿔도 서비스는 그대로 쓰도록 인터페이스로 둔다.
 */
public interface PlayerRepository {

    /**
     * 닉네임들에 playerId를 붙여 한꺼번에 등록한다. 하나라도 이미 쓰이고 있으면 아무것도 등록하지 않는다.
     * 영문은 대소문자를 구분하지 않고 비교한다.
     *
     * @param nicknames 다듬은 닉네임. 서로 겹치지 않아야 한다
     * @return 등록한 플레이어. nicknames와 같은 순서
     * @throws DuplicateNicknameException 이미 쓰고 있는 닉네임이 있으면
     */
    List<Player> saveAll(List<String> nicknames);

    /** 플레이어를 지워 닉네임을 다시 쓸 수 있게 한다. 없는 플레이어는 무시한다 */
    void deleteAll(Collection<Player> players);

    Optional<Player> findById(long playerId);

    List<Player> findAll();

    boolean existsByNickname(String nickname);
}
