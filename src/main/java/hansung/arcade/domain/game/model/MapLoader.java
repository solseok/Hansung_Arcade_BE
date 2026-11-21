package hansung.arcade.domain.game.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * resources/maps/{id}.txt 를 읽어 {@link GameMap}을 만든다. [item] 층을 빼면 클라이언트와 같은 파일이다.
 *
 * <pre>
 * name=캠프 08
 * [floor]      바닥 층: '.' '_' '|' '=' '!'
 * [block]      블럭 층: 바닥과 같은 크기, '.'은 빈 칸
 * [spawn]      스폰 층: 바닥과 같은 크기, 1~8은 스폰 지점
 * [item]       고정 드롭 층(없어도 됨): 바닥과 같은 크기, '.'은 없음. 부서지는 블럭이 있는 칸에만 적을 수 있다
 *              b 물풍선  f 물줄기  u 울트라  r 롤러 신발  d 빨간 악마  g 장갑
 *              s 신발    t 탱크    n 바늘    h 방패       j 스프링 신발
 * </pre>
 *
 * <p>클라이언트 {@code MapLoader}의 줄임 형식(층 이름 없는 한 층)은 실제 맵에 쓰지 않으므로 서버는 받지 않는다.
 * 클라이언트는 아직 [item] 층을 모르므로, 이 층이 든 맵 파일을 클라이언트에 그대로 옮기면 읽지 못한다.</p>
 */
public final class MapLoader {

    private static final String MAP_DIR = "/maps/";
    private static final String FLOOR = "[floor]";
    private static final String BLOCK = "[block]";
    private static final String SPAWN = "[spawn]";
    private static final String ITEM = "[item]";
    private static final List<String> REQUIRED = List.of(FLOOR, BLOCK, SPAWN);
    /** 읽는 순서. 아이템 층은 블럭 층을 보고 검사하므로 블럭 층보다 뒤에 둔다 */
    private static final List<String> ALL_LAYERS = List.of(FLOOR, BLOCK, SPAWN, ITEM);
    private static final char EMPTY = '.';

    private MapLoader() {
    }

    /** @param id 맵 id. 예) camp/camp08 */
    public static GameMap load(String id) {
        String path = MAP_DIR + id + ".txt";
        try (InputStream in = MapLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("맵 파일을 찾을 수 없습니다: " + path);
            }
            return parse(id, new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException("맵 파일을 읽지 못했습니다: " + path, e);
        }
    }

    public static GameMap parse(String id, List<String> lines) {
        Map<String, List<String>> layers = new HashMap<>();
        List<String> current = null;
        for (String raw : lines) {
            String line = raw.replace("﻿", "").strip();
            if (line.isEmpty() || line.startsWith("name=")) {
                continue;
            }
            if (line.startsWith("[")) {
                if (!ALL_LAYERS.contains(line)) {
                    throw new IllegalArgumentException("맵 %s의 층 이름 %s이(가) 잘못되었습니다.".formatted(id, line));
                }
                if (layers.containsKey(line)) {
                    throw new IllegalArgumentException("맵 %s에 %s 층이 두 번 있습니다.".formatted(id, line));
                }
                current = new ArrayList<>();
                layers.put(line, current);
                continue;
            }
            if (current == null) {
                throw new IllegalArgumentException("맵 %s에 층 이름 없는 줄이 있습니다: %s".formatted(id, line));
            }
            current.add(line);
        }
        for (String layer : ALL_LAYERS) {
            if (layers.get(layer) == null) {
                if (REQUIRED.contains(layer)) {
                    throw new IllegalArgumentException("맵 %s에 %s 층이 없습니다.".formatted(id, layer));
                }
            } else if (layers.get(layer).isEmpty()) {
                throw new IllegalArgumentException("맵 %s의 %s 층이 비어 있습니다.".formatted(id, layer));
            }
        }

        List<String> floorRows = layers.get(FLOOR);
        int rows = floorRows.size();
        int columns = floorRows.get(0).length();
        Tile[][] ground = new Tile[rows][columns];
        Tile[][] blocks = new Tile[rows][columns];
        ItemType[][] fixedDrops = new ItemType[rows][columns];
        List<int[]> spawns = new ArrayList<>();
        for (String layer : ALL_LAYERS) {
            List<String> layerRows = layers.get(layer);
            if (layerRows == null) {
                continue;
            }
            if (layerRows.size() != rows) {
                throw new IllegalArgumentException("맵 %s의 %s 층이 %d줄입니다. %d줄이어야 합니다."
                        .formatted(id, layer, layerRows.size(), rows));
            }
            for (int row = 0; row < rows; row++) {
                String line = layerRows.get(row);
                if (line.length() != columns) {
                    throw new IllegalArgumentException("맵 %s의 %s 층 %d번째 줄이 %d칸입니다. %d칸이어야 합니다."
                            .formatted(id, layer, row + 1, line.length(), columns));
                }
                for (int col = 0; col < columns; col++) {
                    char c = line.charAt(col);
                    switch (layer) {
                        case FLOOR -> ground[row][col] = groundTile(id, c, col, row);
                        case BLOCK -> blocks[row][col] = c == EMPTY ? null : blockTile(id, c, col, row);
                        case SPAWN -> {
                            if (c >= '1' && c <= '8') {
                                spawns.add(new int[]{c - '0', col, row});
                            } else if (c != EMPTY) {
                                throw new IllegalArgumentException("맵 %s의 스폰 층 (%d, %d)에 '%c'가 있습니다."
                                        .formatted(id, col, row, c));
                            }
                        }
                        case ITEM -> fixedDrops[row][col] = c == EMPTY ? null : fixedDrop(id, c, col, row, blocks[row][col]);
                        default -> throw new IllegalStateException("읽는 법을 정하지 않은 층입니다: " + layer);
                    }
                }
            }
        }

        spawns.sort(Comparator.comparingInt(spawn -> spawn[0]));
        List<Cell> spawnCells = new ArrayList<>();
        for (int i = 0; i < spawns.size(); i++) {
            int[] spawn = spawns.get(i);
            if (spawn[0] != i + 1) {
                throw new IllegalArgumentException("맵 %s의 스폰 번호는 1부터 빠짐없이 한 번씩 써야 합니다.".formatted(id));
            }
            spawnCells.add(new Cell(spawn[1], spawn[2]));
        }
        if (spawnCells.isEmpty()) {
            throw new IllegalArgumentException("맵 %s에 스폰 지점이 없습니다.".formatted(id));
        }
        return new GameMap(id, ground, blocks, fixedDrops, spawnCells);
    }

    /** 고정 드롭은 부서지는 블럭 칸에만 둘 수 있다. 블럭 층을 먼저 읽은 뒤 부른다 */
    private static ItemType fixedDrop(String id, char c, int col, int row, Tile block) {
        ItemType item = ItemType.fromSymbol(c);
        if (item == null) {
            throw new IllegalArgumentException("맵 %s의 아이템 층 (%d, %d)에 '%c'가 있습니다.".formatted(id, col, row, c));
        }
        if (block == null || !block.isDestructible()) {
            throw new IllegalArgumentException("맵 %s의 아이템 층 (%d, %d)은 부서지는 블럭이 없는 칸입니다."
                    .formatted(id, col, row));
        }
        return item;
    }

    private static Tile groundTile(String id, char c, int col, int row) {
        Tile tile = Tile.fromSymbol(c);
        if (tile == null || !tile.isGround()) {
            throw new IllegalArgumentException("맵 %s의 바닥 층 (%d, %d)에 '%c'가 있습니다.".formatted(id, col, row, c));
        }
        return tile;
    }

    private static Tile blockTile(String id, char c, int col, int row) {
        Tile tile = Tile.fromSymbol(c);
        if (tile == null || tile.isGround()) {
            throw new IllegalArgumentException("맵 %s의 블럭 층 (%d, %d)에 '%c'가 있습니다.".formatted(id, col, row, c));
        }
        return tile;
    }
}
