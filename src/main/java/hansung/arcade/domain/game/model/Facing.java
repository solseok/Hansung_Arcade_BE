package hansung.arcade.domain.game.model;

/**
 * 플레이어가 바라보는 방향. 메시지에는 이름 그대로(UP 등) 쓴다.
 */
public enum Facing {
    UP(0, -1),
    DOWN(0, 1),
    LEFT(-1, 0),
    RIGHT(1, 0);

    private final int dx;
    private final int dy;

    Facing(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }

    public int dx() {
        return dx;
    }

    public int dy() {
        return dy;
    }

    /** 이름에 해당하는 방향. 없으면 null */
    public static Facing parse(String name) {
        for (Facing facing : values()) {
            if (facing.name().equals(name)) {
                return facing;
            }
        }
        return null;
    }
}
