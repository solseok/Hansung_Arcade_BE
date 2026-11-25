package hansung.arcade.domain.game.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 경기를 열 때 참가자마다 맵의 스폰 번호(1부터)를 정한다.
 *
 * <p>보통 맵은 스폰 번호를 무작위로 섞어 앞에서부터 나눠 준다.</p>
 *
 * <p>캠프 08처럼 진영이 나뉜 맵({@link GameMaps#sides})에서 두 팀이 붙으면 팀마다 한 진영에서 시작한다.
 * 실제 캠프 08 4:4 영상(라운드 시작 장면 두 판)에서 같은 팀이 모두 가운데 철조망 한쪽에서 시작했고, 어느 팀이 어느 쪽인지는
 * 판마다 달랐다. 그래서 진영은 경기마다 무작위로 팀에 주고, 진영 안의 자리도 무작위로 나눈다.
 * 한 팀이 진영 자리(4칸)보다 많으면 넘친 사람은 상대 진영에 남은 자리에서 시작한다 (나무위키 "캠프 08": 팀원이 5명이 넘어가면
 * 상대방 팀에 침입). 세 팀 이상이면 진영을 나눌 수 없으므로 보통 맵처럼 섞는다.</p>
 */
public final class SpawnPlan {

    private SpawnPlan() {
    }

    /**
     * @param mapId      실제 맵 id
     * @param teams      참가자 차례대로 각자의 팀
     * @param spawnCount 맵의 스폰 수
     * @return 참가자 차례대로 스폰 번호 (1부터, 겹치지 않는다)
     * @throws IllegalArgumentException 스폰이 모자라면
     */
    public static List<Integer> assign(String mapId, List<Team> teams, int spawnCount, RandomGenerator random) {
        if (teams.size() > spawnCount) {
            throw new IllegalArgumentException("맵의 스폰 자리가 모자랍니다: " + mapId);
        }
        List<List<Integer>> sides = GameMaps.sides(mapId);
        List<Team> distinct = teams.stream().distinct().toList();
        if (sides.size() == 2 && distinct.size() == 2) {
            return bySide(sides, distinct, teams, random);
        }
        List<Integer> numbers = new ArrayList<>();
        for (int i = 1; i <= spawnCount; i++) {
            numbers.add(i);
        }
        Collections.shuffle(numbers, random);
        return List.copyOf(numbers.subList(0, teams.size()));
    }

    /** 두 팀에 진영을 하나씩 무작위로 주고, 진영 자리가 모자란 팀은 상대 진영에 남은 자리를 쓴다 */
    private static List<Integer> bySide(List<List<Integer>> sides, List<Team> distinct, List<Team> teams,
                                        RandomGenerator random) {
        List<List<Integer>> order = new ArrayList<>(sides);
        Collections.shuffle(order, random);
        Map<Team, Deque<Integer>> own = new LinkedHashMap<>();
        for (int i = 0; i < distinct.size(); i++) {
            List<Integer> side = new ArrayList<>(order.get(i));
            Collections.shuffle(side, random);
            own.put(distinct.get(i), new ArrayDeque<>(side));
        }
        Integer[] assigned = new Integer[teams.size()];
        List<Integer> overflow = new ArrayList<>();
        for (int i = 0; i < teams.size(); i++) {
            Integer spawn = own.get(teams.get(i)).poll();
            if (spawn == null) {
                overflow.add(i);
            } else {
                assigned[i] = spawn;
            }
        }
        for (int i : overflow) {
            Team other = distinct.get(0).equals(teams.get(i)) ? distinct.get(1) : distinct.get(0);
            assigned[i] = own.get(other).poll();
        }
        return List.of(assigned);
    }
}
