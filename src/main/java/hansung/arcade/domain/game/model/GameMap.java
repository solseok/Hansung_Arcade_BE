package hansung.arcade.domain.game.model;

import java.util.List;

/**
 * 경기 한 판의 맵 사본. 경기마다 새로 읽으므로 블럭이 부서져도 다른 경기에는 영향이 없다.
 * 칸마다 바닥 층(바뀌지 않음)과 블럭 층(비면 null)이 있다. 맵 바깥은 막힌 칸이다.
 */
public final class GameMap {

    public static final int TILE_SIZE = 40;

    private final String id;
    private final Tile[][] ground;
    private final Tile[][] blocks;
    private final List<Cell> spawns;

    GameMap(String id, Tile[][] ground, Tile[][] blocks, List<Cell> spawns) {
        this.id = id;
        this.ground = ground;
        this.blocks = blocks;
        this.spawns = List.copyOf(spawns);
    }

    public String id() {
        return id;
    }

    public int columns() {
        return ground[0].length;
    }

    public int rows() {
        return ground.length;
    }

    public double pixelWidth() {
        return columns() * (double) TILE_SIZE;
    }

    public double pixelHeight() {
        return rows() * (double) TILE_SIZE;
    }

    public boolean isInside(int col, int row) {
        return col >= 0 && col < columns() && row >= 0 && row < rows();
    }

    /** 위에 보이는 타일: 블럭이 있으면 블럭, 없으면 바닥 */
    public Tile tileAt(int col, int row) {
        Tile block = blocks[row][col];
        return block != null ? block : ground[row][col];
    }

    public boolean isSolid(int col, int row) {
        return !isInside(col, row) || tileAt(col, row).isSolid();
    }

    /**
     * 물줄기로 한 번 맞힌다. 부서지는 블럭만 약해지거나 부서진다.
     *
     * @return 칸이 바뀌었는지
     */
    public boolean hitBlock(int col, int row) {
        if (!isInside(col, row) || blocks[row][col] == null || !blocks[row][col].isDestructible()) {
            return false;
        }
        blocks[row][col] = blocks[row][col].afterHit();
        return true;
    }

    /** 스폰 칸. 맵 파일의 1, 2, 3 … 순서 */
    public List<Cell> spawns() {
        return spawns;
    }

    public static int colAt(double pixelX) {
        return (int) Math.floor(pixelX / TILE_SIZE);
    }

    public static int rowAt(double pixelY) {
        return (int) Math.floor(pixelY / TILE_SIZE);
    }

    public static double centerX(int col) {
        return col * TILE_SIZE + TILE_SIZE / 2.0;
    }

    public static double centerY(int row) {
        return row * TILE_SIZE + TILE_SIZE / 2.0;
    }
}
