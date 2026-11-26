package hansung.arcade.domain.game.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnPlanTest {

    private static final String CAMP = "camp/camp08";
    private static final Set<Integer> LEFT = Set.of(1, 2, 3, 4);
    private static final Set<Integer> RIGHT = Set.of(5, 6, 7, 8);

    /**
     * 판마다 다른 난수. 0, 1, 2 … 처럼 이어진 시드는 java.util.Random의 첫 값들이 서로 닮아(진영 고르기가 한쪽으로 쏠린다)
     * 시드를 난수로 뽑는다 (서버는 한 Random을 계속 쓴다)
     */
    private static final Random SEEDS = new Random(20261125);

    private static Random nextRandom() {
        return new Random(SEEDS.nextLong());
    }

    private static List<Team> teams(int red, int blue) {
        List<Team> teams = new ArrayList<>();
        for (int i = 0; i < red; i++) {
            teams.add(Team.RED);
        }
        for (int i = 0; i < blue; i++) {
            teams.add(Team.BLUE);
        }
        return teams;
    }

    /** 참가자 차례대로 받은 스폰 번호 가운데 그 팀의 것만 */
    private static Set<Integer> spawnsOf(List<Team> teams, List<Integer> spawns, Team team) {
        Set<Integer> picked = new HashSet<>();
        for (int i = 0; i < teams.size(); i++) {
            if (teams.get(i) == team) {
                picked.add(spawns.get(i));
            }
        }
        return picked;
    }

    @Test
    void 캠프_08에서_두_팀이면_팀마다_철조망_한쪽_진영에서_시작한다() {
        List<Team> teams = teams(4, 4);
        Set<String> redSides = new HashSet<>();
        Set<Integer> redFirstSpawns = new HashSet<>();
        for (int seed = 0; seed < 200; seed++) {
            List<Integer> spawns = SpawnPlan.assign(CAMP, teams, 8, nextRandom());

            Set<Integer> red = spawnsOf(teams, spawns, Team.RED);
            Set<Integer> blue = spawnsOf(teams, spawns, Team.BLUE);
            assertTrue(red.equals(LEFT) && blue.equals(RIGHT) || red.equals(RIGHT) && blue.equals(LEFT),
                    "seed=" + seed + " " + spawns);
            redSides.add(red.equals(LEFT) ? "left" : "right");
            redFirstSpawns.add(spawns.get(0));
        }
        // 어느 팀이 어느 진영인지, 진영 안의 자리는 경기마다 무작위다
        assertEquals(Set.of("left", "right"), redSides);
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8), redFirstSpawns);
    }

    @Test
    void 캠프_08에서_한_팀이_네_명보다_많으면_넘친_사람은_상대_진영의_남은_자리에서_시작한다() {
        List<Team> teams = teams(5, 3);
        for (int seed = 0; seed < 100; seed++) {
            List<Integer> spawns = SpawnPlan.assign(CAMP, teams, 8, nextRandom());

            assertEquals(8, Set.copyOf(spawns).size());
            Set<Integer> blue = spawnsOf(teams, spawns, Team.BLUE);
            Set<Integer> blueSide = LEFT.containsAll(blue) ? LEFT : RIGHT;
            assertTrue(blueSide.containsAll(blue), "파랑 셋은 한 진영 안에 있다: " + spawns);
            Set<Integer> red = spawnsOf(teams, spawns, Team.RED);
            Set<Integer> redSide = blueSide == LEFT ? RIGHT : LEFT;
            assertTrue(red.containsAll(redSide), "빨강은 자기 진영 네 칸을 모두 쓴다: " + spawns);
        }
    }

    @Test
    void 캠프_08에서_큰_팀의_누가_상대_진영으로_넘칠지는_참가자_차례가_아니라_무작위다() {
        List<Team> teams = teams(5, 3);
        Set<Integer> overflowed = new HashSet<>();
        for (int seed = 0; seed < 200; seed++) {
            List<Integer> spawns = SpawnPlan.assign(CAMP, teams, 8, nextRandom());
            Set<Integer> blue = spawnsOf(teams, spawns, Team.BLUE);
            Set<Integer> blueSide = LEFT.containsAll(blue) ? LEFT : RIGHT;
            for (int i = 0; i < 5; i++) {
                if (blueSide.contains(spawns.get(i))) {
                    overflowed.add(i);
                }
            }
        }
        // 빨강 다섯 명 모두 한 번씩은 파랑 진영에서 시작한다
        assertEquals(Set.of(0, 1, 2, 3, 4), overflowed);
    }

    @Test
    void 사람이_두_진영_자리를_합친_것보다_많으면_진영을_나누지_않고_섞는다() {
        // 맵 파일 스폰이 진영 자리(8)보다 많아진 경우. 넘친 사람이 앉을 자리가 없어 섞어 나눈다
        List<Team> teams = teams(6, 3);
        List<Integer> spawns = SpawnPlan.assign(CAMP, teams, 9, nextRandom());
        assertEquals(9, Set.copyOf(spawns).size());
        assertTrue(spawns.stream().allMatch(n -> n >= 1 && n <= 9), spawns.toString());
    }

    @Test
    void 캠프_08에서_한_명씩_붙어도_서로_다른_진영에서_시작한다() {
        List<Team> teams = teams(1, 1);
        for (int seed = 0; seed < 50; seed++) {
            List<Integer> spawns = SpawnPlan.assign(CAMP, teams, 8, nextRandom());
            assertTrue(LEFT.contains(spawns.get(0)) != LEFT.contains(spawns.get(1)), "seed=" + seed + " " + spawns);
        }
    }

    @Test
    void 세_팀_이상이거나_진영이_없는_맵이면_스폰을_섞어_나눈다() {
        List<Team> three = List.of(Team.RED, Team.BLUE, Team.GREEN, Team.RED);
        Set<Integer> mixedFirst = new HashSet<>();
        for (int seed = 0; seed < 200; seed++) {
            List<Integer> camp = SpawnPlan.assign(CAMP, three, 8, nextRandom());
            assertEquals(4, Set.copyOf(camp).size());
            List<Integer> village = SpawnPlan.assign("village/village10", teams(3, 3), 6, nextRandom());
            assertEquals(6, Set.copyOf(village).size());
            assertTrue(village.stream().allMatch(n -> n >= 1 && n <= 6), village.toString());
            mixedFirst.add(village.get(0));
        }
        assertEquals(Set.of(1, 2, 3, 4, 5, 6), mixedFirst);
        // 패트릿 14에서는 4:4라도 같은 팀이 한쪽에 모이지 않는 판이 나온다
        boolean split = false;
        for (int seed = 0; seed < 50 && !split; seed++) {
            List<Team> teams = teams(4, 4);
            Set<Integer> red = spawnsOf(teams, SpawnPlan.assign("patrit/patrit14", teams, 8, nextRandom()),
                    Team.RED);
            split = !red.equals(LEFT) && !red.equals(RIGHT);
        }
        assertTrue(split);
    }

    @Test
    void 스폰보다_사람이_많으면_실패한다() {
        assertThrows(IllegalArgumentException.class,
                () -> SpawnPlan.assign("village/village10", teams(4, 3), 6, new Random(1)));
    }

    @Test
    void 진영은_캠프_08만_왼쪽_1에서_4번_오른쪽_5에서_8번이다() {
        assertEquals(List.of(List.of(1, 2, 3, 4), List.of(5, 6, 7, 8)), GameMaps.sides(CAMP));
        assertEquals(List.of(), GameMaps.sides("patrit/patrit14"));
        assertEquals(List.of(), GameMaps.sides("village/village10"));
        // 맵 파일에서도 1~4번은 철조망(7열) 왼쪽, 5~8번은 오른쪽이다
        GameMap camp = MapLoader.load(CAMP);
        for (int n = 1; n <= 8; n++) {
            assertEquals(n <= 4, camp.spawns().get(n - 1).col() < 7, "spawn " + n);
        }
    }
}
