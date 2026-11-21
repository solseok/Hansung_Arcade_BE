package hansung.arcade.domain.game.business;

import hansung.arcade.domain.game.model.Cell;
import hansung.arcade.domain.game.model.Facing;
import hansung.arcade.domain.game.model.GameMap;
import hansung.arcade.domain.game.model.GameMaps;
import hansung.arcade.domain.game.model.MapLoader;
import hansung.arcade.domain.game.model.Mascot;
import hansung.arcade.domain.game.model.Match;
import hansung.arcade.domain.game.model.MatchEvent;
import hansung.arcade.domain.game.model.MatchPlayer;
import hansung.arcade.domain.game.model.Team;
import hansung.arcade.global.exception.GameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static hansung.arcade.global.exception.CommonErrorCode.WRONG_STATE;

/**
 * 진행 중인 경기들을 들고, 인게임 메시지와 타이머를 해당 {@link Match}로 넘긴다.
 *
 * <p>경기 하나의 판정과 푸시 보내기는 그 경기 lock 안에서 함께 한다. 그래서 받는 스레드(MOVE 등)와 타이머 스레드에서
 * 생긴 푸시가 판정 순서대로 나간다. {@link EventSender}는 송신 큐에 넣기만 하므로 lock을 오래 잡지 않는다.</p>
 *
 * <p>방 API가 아직 없으므로 경기는 {@link #start}로 직접 연다. 방을 만들면 GAME_START 처리에서 이것을 부르고,
 * {@link #onFinished}로 경기가 끝난 방을 대기 상태로 돌린다.</p>
 */
public final class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    /**
     * 경기에 들어갈 플레이어 한 명.
     *
     * @param character 캐릭터 id 또는 {@code random}
     */
    public record Participant(long playerId, long connectionId, String nickname, String character, Team team) {
    }

    private final EventSender sender;
    private final LongSupplier clock;
    private final Random random;
    private final List<Match> matches = new CopyOnWriteArrayList<>();
    private final Map<Long, Match> byConnection = new ConcurrentHashMap<>();
    private final AtomicLong lastMatchId = new AtomicLong();
    /** 끝났지만 아직 {@link #finishedListener}에 알리지 않은 경기 */
    private final Set<Match> pendingFinished = ConcurrentHashMap.newKeySet();
    private volatile Consumer<Match> finishedListener = match -> {
    };

    /**
     * @param clock 지금 시각(ms). 단조 증가하면 된다
     */
    public GameService(EventSender sender, LongSupplier clock, Random random) {
        this.sender = Objects.requireNonNull(sender);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    /** 경기가 끝나면(GAME_OVER를 보낸 뒤, 또는 모두 나가서) 불린다. 방 API가 방을 대기 상태로 돌릴 때 쓴다 */
    public void onFinished(Consumer<Match> listener) {
        this.finishedListener = Objects.requireNonNull(listener);
    }

    /**
     * 경기를 열고 GAME_STARTED를 보낸다. 맵·캐릭터의 {@code random}은 여기서 실제 값으로 정하고, 스폰 자리는 무작위로 나눈다.
     *
     * @param mapId 맵 id 또는 {@code random}
     * @throws IllegalArgumentException 팀이 둘 미만, 모르는 맵·캐릭터, 정원 초과, 이미 경기 중인 연결
     */
    public Match start(String mapId, List<Participant> participants) {
        if (participants.isEmpty()) {
            throw new IllegalArgumentException("플레이어가 없습니다.");
        }
        if (participants.stream().map(Participant::team).distinct().count() < 2) {
            throw new IllegalArgumentException("두 팀 이상이어야 경기를 시작할 수 있습니다.");
        }
        String actualMapId = GameMaps.RANDOM.equals(mapId) ? GameMaps.pick(participants.size(), random) : mapId;
        if (!GameMaps.exists(actualMapId)) {
            throw new IllegalArgumentException("지원하지 않는 맵입니다: " + mapId);
        }
        if (participants.size() > GameMaps.maxPlayers(actualMapId)) {
            throw new IllegalArgumentException("%s 맵에는 %d명까지 들어갑니다.".formatted(actualMapId,
                    GameMaps.maxPlayers(actualMapId)));
        }
        GameMap map = MapLoader.load(actualMapId);
        if (participants.size() > map.spawns().size()) {
            throw new IllegalArgumentException("맵의 스폰 자리가 모자랍니다: " + actualMapId);
        }

        List<Integer> spawnNumbers = new ArrayList<>();
        for (int i = 1; i <= map.spawns().size(); i++) {
            spawnNumbers.add(i);
        }
        Collections.shuffle(spawnNumbers, random);

        List<MatchPlayer> players = new ArrayList<>();
        for (int i = 0; i < participants.size(); i++) {
            Participant p = participants.get(i);
            Mascot mascot = Mascot.RANDOM.equals(p.character()) ? Mascot.pick(random) : Mascot.of(p.character());
            int spawn = spawnNumbers.get(i);
            Cell spawnCell = map.spawns().get(spawn - 1);
            players.add(new MatchPlayer(p.playerId(), p.connectionId(), p.nickname(), mascot, p.team(), spawn, spawnCell));
        }
        Match match = new Match(lastMatchId.incrementAndGet(), map, players);

        Set<Long> connections = new HashSet<>(match.connectionIds());
        synchronized (match) {
            for (Long connectionId : connections) {
                if (byConnection.putIfAbsent(connectionId, match) != null) {
                    connections.forEach(id -> byConnection.remove(id, match));
                    throw new IllegalArgumentException("이미 경기 중인 연결입니다: #" + connectionId);
                }
            }
            matches.add(match);
            log.info("경기 #{} 시작: {} {}명", match.matchId(), actualMapId, players.size());
            long now = clock.getAsLong();
            deliver(match, match.start(now));
            // 이미 끊긴 연결은 끊김 정리가 경기 등록보다 먼저 돌아 놓쳤을 수 있다. 로딩 시간 초과까지 기다리지 않고 바로 뺀다
            for (Long connectionId : connections) {
                if (!sender.isConnected(connectionId)) {
                    deliver(match, match.leave(connectionId, "DISCONNECT", false, now));
                }
            }
        }
        notifyIfFinished(match);
        return match;
    }

    /** 이 연결이 들어가 있는 경기. 없으면 null */
    public Match matchOf(long connectionId) {
        return byConnection.get(connectionId);
    }

    /**
     * LOADED.
     *
     * @throws GameException 경기 중이 아니거나 이미 보냈으면 WRONG_STATE
     */
    public void loaded(long connectionId) {
        Match match = requireMatch(connectionId);
        synchronized (match) {
            deliver(match, match.loaded(connectionId, clock.getAsLong()));
        }
        notifyIfFinished(match);
    }

    /** MOVE. 경기 중이 아니거나 맞지 않으면 조용히 버린다 */
    public void move(long connectionId, long playerId, double x, double y, Facing facing, boolean moving) {
        Match match = byConnection.get(connectionId);
        if (match == null) {
            return;
        }
        synchronized (match) {
            deliver(match, match.move(connectionId, playerId, x, y, facing, moving, clock.getAsLong()));
        }
        notifyIfFinished(match);
    }

    /** BALLOON_PLACE. 경기 중이 아니거나 놓을 수 없으면 조용히 버린다 */
    public void placeBalloon(long connectionId, long playerId, int col, int row) {
        Match match = byConnection.get(connectionId);
        if (match == null) {
            return;
        }
        synchronized (match) {
            deliver(match, match.placeBalloon(connectionId, playerId, col, row, clock.getAsLong()));
        }
        notifyIfFinished(match);
    }

    /**
     * GAME_EXIT. 이 연결의 플레이어가 경기에서 나간다.
     *
     * @throws GameException 경기 중이 아니면 WRONG_STATE
     */
    public void exit(long connectionId) {
        Match match = requireMatch(connectionId);
        synchronized (match) {
            deliver(match, match.leave(connectionId, "EXIT", false, clock.getAsLong()));
        }
        notifyIfFinished(match);
    }

    /** 연결이 끊겼다 (BYE 포함). 경기 중이 아니면 아무 일도 없다 */
    public void disconnect(long connectionId) {
        Match match = byConnection.get(connectionId);
        if (match == null) {
            return;
        }
        synchronized (match) {
            deliver(match, match.leave(connectionId, "DISCONNECT", false, clock.getAsLong()));
        }
        notifyIfFinished(match);
    }

    /** 타이머가 수십 ms마다 부른다. 경기 하나가 실패해도 나머지는 계속 돈다 */
    public void tick() {
        for (Match match : matches) {
            try {
                synchronized (match) {
                    deliver(match, match.tick(clock.getAsLong()));
                }
                notifyIfFinished(match);
            } catch (RuntimeException e) {
                log.error("경기 #{} 타이머 처리 중 오류", match.matchId(), e);
            }
        }
    }

    /** 진행 중인 경기 수 */
    public int activeMatches() {
        return matches.size();
    }

    private Match requireMatch(long connectionId) {
        Match match = byConnection.get(connectionId);
        if (match == null) {
            throw new GameException(WRONG_STATE, "게임 중이 아닙니다.");
        }
        return match;
    }

    /**
     * 경기 lock 안에서 부른다. 푸시를 보내고, 경기에서 빠진 연결과 끝난 경기를 정리한다.
     *
     * <p>주의: 송신 큐가 넘치면 {@code Connection}이 이 스레드에서 끊김 리스너를 부른다. 그래서 경기 lock → 세션 lock
     * 순서가 생긴다. 세션·방 lock을 잡은 채 경기 메서드를 부르지 않는다.</p>
     */
    private void deliver(Match match, List<MatchEvent> events) {
        for (MatchEvent event : events) {
            try {
                sender.send(event);
            } catch (RuntimeException e) {
                log.error("경기 #{} {} 보내기 실패", match.matchId(), event.type(), e);
            }
        }
        List<Long> remaining = match.connectionIds();
        boolean finished = match.isFinished();
        byConnection.entrySet().removeIf(entry ->
                entry.getValue() == match && (finished || !remaining.contains(entry.getKey())));
        if (finished && matches.remove(match)) {
            log.info("경기 #{} 끝", match.matchId());
            pendingFinished.add(match);
        }
    }

    /**
     * 끝난 경기를 {@link #onFinished} 리스너에 알린다. 경기 lock 밖에서 부른다.
     * 방 API가 이 안에서 방 lock을 잡으므로, 경기 lock을 잡은 채 부르면 lock 순서가 엇갈릴 수 있다.
     */
    private void notifyIfFinished(Match match) {
        if (pendingFinished.remove(match)) {
            try {
                finishedListener.accept(match);
            } catch (RuntimeException e) {
                log.error("경기 #{} 종료 처리 중 오류", match.matchId(), e);
            }
        }
    }
}
