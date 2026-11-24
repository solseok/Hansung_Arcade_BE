package hansung.arcade.domain.room.web.controller;

import hansung.arcade.core.net.Connection;
import hansung.arcade.core.net.ConnectionListener;
import hansung.arcade.domain.room.business.RoomService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** 연결 종료 정리를 executor로 넘겨 진행 중인 방 푸시 순서를 보존한다. */
public final class RoomListener implements ConnectionListener {

    private static final Logger log = LoggerFactory.getLogger(RoomListener.class);

    private final RoomService roomService;
    private final Executor executor;

    public RoomListener(RoomService roomService, Executor executor) {
        this.roomService = Objects.requireNonNull(roomService);
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public void onClose(Connection connection, String reason) {
        long connectionId = connection.id();
        try {
            executor.execute(() -> roomService.disconnect(connectionId));
        } catch (RejectedExecutionException e) {
            // 서버가 꺼지는 중이다. 경기도 함께 사라지므로 정리할 것이 없다
            log.debug("#{} 방 정리를 건너뜀 (서버 종료 중)", connectionId);
        }
    }
}
