package hansung.arcade.domain.game.model;

import java.util.List;
import java.util.Optional;

/**
 * 경기 한 판의 맵 사본. 경기마다 새로 읽으므로 블럭이 부서져도 다른 경기에는 영향이 없다.
 * 칸마다 바닥 층(바뀌지 않음)과 블럭 층(비면 null)이 있다. 맵 바깥은 막힌 칸이다.
 * 일부 블럭은 다 부서질 때 늘 같은 아이템을 떨어뜨린다(고정 드롭).
 */
public final class GameMap {

    public static final int TILE_SIZE = 40;

    private final String id;
    private final Tile[][] ground;
    private final Tile[][] blocks;
    private final ItemType[][] fixedDrops;
    private final List<Cell> spawns;

    GameMap(String id, Tile[][] ground, Tile[][] blocks, ItemType[][] fixedDrops, List<Cell> spawns) {
        this.id = id;
        this.ground = ground;
        this.blocks = blocks;
        this.fixedDrops = fixedDrops;
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

    /**
     * 이 칸의 블럭이 다 부서질 때 늘 나오는 아이템. 맵 파일 [item] 층에 적은 값이다.
     * 고정 드롭이 아닌 칸에서 아이템이 나올지는 확률로 정한다.
     *
     * <p>칸 좌표에 묶여 있다. 블럭이 다 부서져 떨어뜨리면 {@link #takeFixedDrop}이 그 칸을 비운다.
     * 상자 밀기를 만들 때, 상자(캠프 울트라 칸은 모두 밀리는 상자다)가 옮겨 가면 고정 드롭도 함께 옮겨야 한다.</p>
     *
     * @return 고정 드롭이 없거나 맵 바깥이면 비어 있다
     */
    public Optional<ItemType> fixedDrop(int col, int row) {
        if (!isInside(col, row)) {
            return Optional.empty();
        }
        return Optional.ofNullable(fixedDrops[row][col]);
    }

    /** 고정 드롭을 꺼내고 그 칸을 비운다. 같은 칸에서 두 번 떨어지지 않게 한다 */
    Optional<ItemType> takeFixedDrop(int col, int row) {
        Optional<ItemType> drop = fixedDrop(col, row);
        if (drop.isPresent()) {
            fixedDrops[row][col] = null;
        }
        return drop;
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
