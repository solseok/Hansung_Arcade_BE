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
    PUSH_BOX('P', true, true),
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
