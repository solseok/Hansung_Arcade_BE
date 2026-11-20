package hansung.arcade.domain.player.repository;

import hansung.arcade.domain.player.exception.DuplicateNicknameException;
import hansung.arcade.domain.player.model.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 메모리에만 두는 {@link PlayerRepository}. 동시 접속이 10명도 안 되므로 HashMap 두 개를 한 lock(this)으로 지킨다.
 * 서버를 끄면 사라지는데, 접속해 있는 동안만 의미 있는 정보라 괜찮다.
 *
 * <p>2P의 두 닉네임은 "검사 → 등록"을 한 lock 안에서 해야 한꺼번에 성공하거나 실패한다.
 * {@code ConcurrentHashMap.putIfAbsent}를 두 번 부르면 사이에 다른 연결이 끼어들 수 있다.
 */
public final class InMemoryPlayerRepository implements PlayerRepository {

    private final Map<Long, Player> byId = new HashMap<>();
    private final Map<String, Player> byNickname = new HashMap<>();
    private long lastPlayerId;

    @Override
    public synchronized List<Player> saveAll(List<String> nicknames) {
        Set<String> keys = new HashSet<>();
        for (String nickname : nicknames) {
            String key = Player.nicknameKey(nickname);
            if (byNickname.containsKey(key) || !keys.add(key)) {
                throw new DuplicateNicknameException(nickname);
            }
        }

        List<Player> saved = new ArrayList<>(nicknames.size());
        for (String nickname : nicknames) {
            Player player = new Player(++lastPlayerId, nickname);
            byId.put(player.playerId(), player);
            byNickname.put(Player.nicknameKey(nickname), player);
            saved.add(player);
        }
        return List.copyOf(saved);
    }

    @Override
    public synchronized void deleteAll(Collection<Player> players) {
        for (Player player : players) {
            // 같은 id의 같은 플레이어일 때만 지운다 (닉네임을 다른 사람이 새로 쓰고 있으면 건드리지 않게)
            if (byId.remove(player.playerId(), player)) {
                byNickname.remove(Player.nicknameKey(player.nickname()), player);
            }
        }
    }

    @Override
    public synchronized Optional<Player> findById(long playerId) {
        return Optional.ofNullable(byId.get(playerId));
    }

    @Override
    public synchronized List<Player> findAll() {
        return List.copyOf(byId.values());
    }

    @Override
    public synchronized boolean existsByNickname(String nickname) {
        return byNickname.containsKey(Player.nicknameKey(nickname));
    }
}
