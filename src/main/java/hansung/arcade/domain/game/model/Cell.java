package hansung.arcade.domain.game.model;

/**
 * 맵 칸 위치. 0부터, 왼쪽 위가 (0, 0).
 */
public record Cell(int col, int row) {

    /** 가로·세로 칸 수 차이 중 큰 쪽 (대각선 이웃도 1) */
    public int distanceTo(Cell other) {
        return Math.max(Math.abs(col - other.col), Math.abs(row - other.row));
    }
}
