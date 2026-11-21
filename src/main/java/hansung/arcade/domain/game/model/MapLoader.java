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
 * resources/maps/{id}.txt 를 읽어 {@link GameMap}을 만든다. 클라이언트와 같은 파일을 쓴다.
 *
 * <pre>
 * name=캠프 08
 * [floor]      바닥 층: '.' '_' '|' '=' '!'
 * [block]      블럭 층: 바닥과 같은 크기, '.'은 빈 칸
 * [spawn]      스폰 층: 바닥과 같은 크기, 1~8은 스폰 지점
 * </pre>
 *
 * <p>클라이언트 {@code MapLoader}의 줄임 형식(층 이름 없는 한 층)은 실제 맵에 쓰지 않으므로 서버는 받지 않는다.</p>
 */
public final class MapLoader {

    private static final String MAP_DIR = "/maps/";
    private static final String FLOOR = "[floor]";
    private static final String BLOCK = "[block]";
    private static final String SPAWN = "[spawn]";
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
                if (!List.of(FLOOR, BLOCK, SPAWN).contains(line) || layers.containsKey(line)) {
                    throw new IllegalArgumentException("맵 %s의 층 이름 %s이(가) 잘못되었습니다.".formatted(id, line));
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
        for (String layer : List.of(FLOOR, BLOCK, SPAWN)) {
            if (layers.get(layer) == null || layers.get(layer).isEmpty()) {
                throw new IllegalArgumentException("맵 %s에 %s 층이 없습니다.".formatted(id, layer));
            }
        }

        List<String> floorRows = layers.get(FLOOR);
        int rows = floorRows.size();
        int columns = floorRows.get(0).length();
        Tile[][] ground = new Tile[rows][columns];
        Tile[][] blocks = new Tile[rows][columns];
        List<int[]> spawns = new ArrayList<>();
        for (String layer : List.of(FLOOR, BLOCK, SPAWN)) {
            List<String> layerRows = layers.get(layer);
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
                        default -> {
                            if (c >= '1' && c <= '8') {
                                spawns.add(new int[]{c - '0', col, row});
                            } else if (c != EMPTY) {
                                throw new IllegalArgumentException("맵 %s의 스폰 층 (%d, %d)에 '%c'가 있습니다."
                                        .formatted(id, col, row, c));
                            }
                        }
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
        return new GameMap(id, ground, blocks, spawnCells);
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
