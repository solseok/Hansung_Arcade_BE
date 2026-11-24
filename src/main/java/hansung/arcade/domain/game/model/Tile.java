package hansung.arcade.domain.game.model;

/**
 * 맵 한 칸의 종류. 맵 파일에서는 {@link #symbol} 한 글자로 쓴다.
 * 클라이언트 {@code hansung.arcade.ingame.map.Tile}과 기호·성질이 같아야 한다 (서버는 판정에 필요한 것만 둔다).
 */
public enum Tile {
    FLOOR('.', false, false),
    ROAD('_', false, false),
    ROAD_LANE('|', false, false),
    CROSSWALK('=', false, false),
    SPIKE('!', false, false),
    BLOCK('B', true, true),
    BLOCK_2('O', true, true),
    SIDE_BLOCK('-', true, true),
    /** 덤불. 지나갈 수 있고 물줄기도 지나간다 */
    BUSH('T', false, false),
    /** 몸으로 밀면 한 칸 밀리는 상자. 물줄기에 맞으면 부서진다 */
    PUSH_BOX('P', true, true),
    /** 철로 보강된 상자. 세 번 맞아야 부서지고(c → b → a → 바닥), 상자처럼 밀린다 */
    HARD_BLOCK_3('c', true, true),
    HARD_BLOCK_2('b', true, true),
    HARD_BLOCK_1('a', true, true),
    ONE_WAY_RIGHT('>', true, true),
    ONE_WAY_LEFT('<', true, true),
    ONE_WAY_UP('^', true, true),
    ONE_WAY_DOWN('v', true, true),
    STATUE('#', true, false),
    WIRE('W', true, false),
    MISSILE('M', true, false),
    HOUSE_1('H', true, false),
    HOUSE_2('J', true, false),
    HOUSE_3('K', true, false),
    TREE('Y', true, false);

    private final char symbol;
    private final boolean solid;
    private final boolean destructible;

    Tile(char symbol, boolean solid, boolean destructible) {
        this.symbol = symbol;
        this.solid = solid;
        this.destructible = destructible;
    }

    public char symbol() {
        return symbol;
    }

    /** 플레이어·물줄기를 막는지 */
    public boolean isSolid() {
        return solid;
    }

    /** 물줄기에 맞으면 약해지거나 부서지는지 */
    public boolean isDestructible() {
        return destructible;
    }

    /** 바닥 층 타일인지 (블럭이 치워지면 드러나는 칸) */
    public boolean isGround() {
        return switch (this) {
            case FLOOR, ROAD, ROAD_LANE, CROSSWALK, SPIKE -> true;
            default -> false;
        };
    }

    /**
     * 몸으로 밀면 한 칸 밀리는지. 상자와 철로 보강된 상자(c·b·a)다.
     * 보강된 상자는 타일째 옮기므로 남은 맞을 횟수도 그대로 따라간다.
     *
     * <p>클라이언트 Tile.isPushable은 아직 상자만 참이다. 보강된 상자는 클라이언트 BOX_PUSH·BOX_MOVED 연동과 함께
     * 같은 네 타일로 넓힌다. 그 전의 클라이언트는 BOX_PUSH를 보내지 않으므로 이 차이로 맵이 어긋나지 않는다.</p>
     */
    public boolean isPushable() {
        return switch (this) {
            case PUSH_BOX, HARD_BLOCK_3, HARD_BLOCK_2, HARD_BLOCK_1 -> true;
            default -> false;
        };
    }

    /** 물줄기에 한 번 맞은 뒤 남는 블럭. 이번에 부서지면 null, 부서지지 않는 칸은 자기 자신 */
    public Tile afterHit() {
        if (!destructible) {
            return this;
        }
        return switch (this) {
            case HARD_BLOCK_3 -> HARD_BLOCK_2;
            case HARD_BLOCK_2 -> HARD_BLOCK_1;
            default -> null;
        };
    }

    /** 맵 파일 글자에 해당하는 타일. 없으면 null */
    public static Tile fromSymbol(char symbol) {
        for (Tile tile : values()) {
            if (tile.symbol == symbol) {
                return tile;
            }
        }
        return null;
    }
}
