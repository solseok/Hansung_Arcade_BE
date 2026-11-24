package hansung.arcade.domain.game.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchPlayerTest {

    private static MatchPlayer player(String character) {
        return new MatchPlayer(1, 101, "플레이어", Mascot.of(character), Team.RED, 1, new Cell(0, 0));
    }

    @Test
    void 능력치는_캐릭터_시작값에서_시작한다() {
        MatchPlayer player = player("hansungnyangi");

        assertEquals(2, player.balloonCapacity());
        assertEquals(1, player.waterRange());
        assertEquals(4, player.speedLevel());
    }

    @Test
    void 물풍선_물줄기_롤러는_하나씩_오르고_캐릭터_최댓값에서_멈춘다() {
        // kkokko: 물풍선 1~8, 물줄기 1~5, 속도 5~8
        MatchPlayer player = player("kkokko");

        for (int i = 0; i < 10; i++) {
            player.takeItem(ItemType.BUBBLE);
            player.takeItem(ItemType.FLUID);
            player.takeItem(ItemType.ROLLER_SHOE);
        }

        assertEquals(8, player.balloonCapacity());
        assertEquals(5, player.waterRange());
        assertEquals(8, player.speedLevel());
    }

    @Test
    void 울트라와_빨간_악마는_물줄기를_한_번에_최댓값으로_올리고_빨간_악마는_밀기도_준다() {
        MatchPlayer ultra = player("sangzzi");
        MatchPlayer devil = player("sangzzi");

        ultra.takeItem(ItemType.ULTRA);
        devil.takeItem(ItemType.RED_DEVIL);

        assertEquals(7, ultra.waterRange());
        assertFalse(ultra.canPushBalloons());
        assertEquals(7, devil.waterRange());
        assertTrue(devil.canPushBalloons());
    }

    @Test
    void 신발_장갑_탱크는_능력치_대신_상태로_남는다() {
        MatchPlayer player = player("kkukku");

        player.takeItem(ItemType.SHOE);
        player.takeItem(ItemType.GLOVE);
        player.takeItem(ItemType.TANK);

        assertTrue(player.canPushBalloons());
        assertTrue(player.canThrowBalloons());
        assertTrue(player.hasTank());
        assertEquals(List.of(1, 1, 4), List.of(player.balloonCapacity(), player.waterRange(), player.speedLevel()));
    }

    @Test
    void 최댓값이라_오르지_않은_것도_먹은_아이템으로_센다() {
        MatchPlayer player = player("kkokko");
        player.takeItem(ItemType.ULTRA);

        player.takeItem(ItemType.FLUID);
        player.takeItem(ItemType.ULTRA);

        assertEquals(5, player.waterRange());
        assertEquals(List.of(ItemType.ULTRA, ItemType.FLUID, ItemType.ULTRA), player.takenItems());
    }

    @Test
    void 액티브_아이템은_손에_들고_능력치와_흩뿌릴_목록은_그대로다() {
        MatchPlayer player = player("kkukku");

        player.takeItem(ItemType.NEEDLE);

        assertEquals(ItemType.NEEDLE, player.heldItem());
        assertEquals(1, player.heldCount());
        assertEquals(List.of(1, 1, 4), List.of(player.balloonCapacity(), player.waterRange(), player.speedLevel()));
        assertEquals(List.of(), player.takenItems());
    }

    @Test
    void 같은_액티브를_또_먹으면_쌓이고_다른_액티브를_먹으면_바꿔_든다() {
        MatchPlayer player = player("kkukku");

        player.takeItem(ItemType.SHIELD);
        player.takeItem(ItemType.SHIELD);
        assertEquals(List.of(ItemType.SHIELD, 2), List.of(player.heldItem(), player.heldCount()));

        // 패시브는 손을 건드리지 않는다
        player.takeItem(ItemType.BUBBLE);
        assertEquals(List.of(ItemType.SHIELD, 2), List.of(player.heldItem(), player.heldCount()));

        player.takeItem(ItemType.SPRING_SHOE);
        assertEquals(List.of(ItemType.SPRING_SHOE, 1), List.of(player.heldItem(), player.heldCount()));
    }

    @Test
    void 쌓아_드는_개수는_클라이언트처럼_99개에서_멈춘다() {
        MatchPlayer player = player("kkukku");

        for (int i = 0; i < 120; i++) {
            player.takeItem(ItemType.NEEDLE);
        }

        assertEquals(MatchPlayer.MAX_HELD_COUNT, player.heldCount());
        assertEquals(99, MatchPlayer.MAX_HELD_COUNT);
    }

    @Test
    void 손에_든_아이템을_쓰면_하나씩_줄고_다_쓰면_손이_빈다() {
        MatchPlayer player = player("kkukku");
        player.takeItem(ItemType.NEEDLE);
        player.takeItem(ItemType.NEEDLE);

        player.useHeldItem();
        assertEquals(List.of(ItemType.NEEDLE, 1), List.of(player.heldItem(), player.heldCount()));

        player.useHeldItem();
        assertNull(player.heldItem());
        assertEquals(0, player.heldCount());

        // 빈손에 써도 음수가 되지 않는다
        player.useHeldItem();
        assertEquals(0, player.heldCount());
    }

    @Test
    void 터지면_손에_든_아이템은_사라지고_먹은_패시브만_흩뿌릴_목록에_남는다() {
        MatchPlayer player = player("kkukku");
        player.takeItem(ItemType.FLUID);
        player.takeItem(ItemType.SHIELD);

        player.pop(0);

        assertNull(player.heldItem());
        assertEquals(0, player.heldCount());
        assertEquals(List.of(ItemType.FLUID), player.handOverTakenItems());
    }

    @Test
    void 흩뿌릴_아이템을_꺼내면_먹은_아이템이_비고_능력치는_그대로다() {
        MatchPlayer player = player("kkukku");
        player.takeItem(ItemType.BUBBLE);
        player.takeItem(ItemType.SHOE);

        assertEquals(List.of(ItemType.BUBBLE, ItemType.SHOE), player.handOverTakenItems());
        assertEquals(List.of(), player.takenItems());
        assertEquals(2, player.balloonCapacity());
    }
}
