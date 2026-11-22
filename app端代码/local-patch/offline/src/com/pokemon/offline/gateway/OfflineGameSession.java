package com.pokemon.offline.gateway;

import com.pokemon.offline.platform.stereotype.Component;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Unity 与 App 业务之间的进程内会话，不创建网络连接或监听端口。
 *
 * @author tongruiyuan
 * @since 2026-11-21 23:55
 */
@Component
public final class OfflineGameSession {
    private final SprotoProtocol protocol;
    private final GameGatewayService games;
    private final List<byte[]> pending = new ArrayList<>();
    private final Set<Integer> unknown = new HashSet<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "pokemon-offline-tick");
        thread.setDaemon(true);
        return thread;
    });
    private GameGatewayService.Connection connection;
    private DirectReply reply;
    private TransactionBoundary transaction;
    private IOException timerFailure;

    /**
     * 装配原消息编解码器和本机业务分派器。
     * @param protocol 消息编解码器
     * @param games 本机业务分派器
     */
    OfflineGameSession(SprotoProtocol protocol, GameGatewayService games) {
        this.protocol = protocol;
        this.games = games;
    }

    /**
     * 注册 App 私有 SQLite 的事务边界并开始本机计时。
     * @param boundary 本地事务执行器
     */
    public synchronized void useTransaction(TransactionBoundary boundary) {
        if (transaction != null || boundary == null) throw new IllegalStateException("离线事务已装配或无效");
        transaction = boundary;
        timer.scheduleWithFixedDelay(this::tick, 250, 250, TimeUnit.MILLISECONDS);
    }

    /** 创建单一的 Unity 本地会话，替换已退出的旧会话。 */
    public synchronized void connect() {
        if (transaction == null) throw new IllegalStateException("离线存档未初始化");
        connection = new GameGatewayService.Connection();
        reply = new DirectReply();
        pending.clear();
        unknown.clear();
        timerFailure = null;
    }

    /** 退出 Unity 会话；不修改角色资产或删除存档。 */
    public synchronized void disconnect() {
        connection = null;
        reply = null;
        pending.clear();
        unknown.clear();
        timerFailure = null;
    }

    /**
     * 在本地事务中处理一条原协议消息。
     * @param payload 没有 TCP 长度前缀的原消息内容
     * @return 已提交的响应与主动推送，顺序与原业务一致
     * @throws IOException 参数或业务执行失败
     */
    public synchronized List<byte[]> request(byte[] payload) throws IOException {
        requireConnected();
        if (payload == null || payload.length == 0 || payload.length > 65535) throw new IOException("离线消息长度无效");
        SprotoProtocol.Request request = protocol.decodeRequest(payload);
        if (request.type == 3 || request.type == 3006) {
            android.util.Log.i("PokemonOffline", "开始本地请求 C" + request.type + " session=" + request.session
                    + (request.type == 3006 ? " sid=" + request.body.get("sid") : ""));
        }
        try {
            committed(() -> {
                if (!games.handle(connection, request, reply)) {
                    unknown.add(request.type);
                    Map<String, Object> failure = protocol.unsupported(request);
                    if (!failure.isEmpty()) reply.send(request, failure);
                }
            });
        } catch (IOException | RuntimeException failure) {
            // committed 已完成回滚并丢弃本次消息；只回原会话失败，不重放资产操作。
            android.util.Log.e("PokemonOffline", "本地请求失败 C" + request.type, failure);
            Map<String, Object> body = protocol.localFailure(request);
            if (body.isEmpty()) throw new IOException("本地请求执行失败 C" + request.type, failure);
            pending.add(protocol.response(request, body));
        }
        return drain();
    }

    /**
     * 提取 App 已完成提交的主动消息。
     * @return 按提交顺序排列的消息
     * @throws IOException 计时业务失败或会话未开启
     */
    public synchronized List<byte[]> poll() throws IOException {
        requireConnected();
        if (timerFailure != null) {
            IOException failure = timerFailure;
            timerFailure = null;
            throw failure;
        }
        return drain();
    }

    /** 释放应用关闭时的后台计时资源。 */
    public synchronized void stop() {
        timer.shutdownNow();
        disconnect();
    }

    /** 本地事务边界。 */
    public interface TransactionBoundary {
        /**
         * 提交一次本地业务操作，失败则回滚。
         * @param work 本次业务操作
         * @throws IOException 执行或提交失败
         */
        void run(RequestWork work) throws IOException;
    }

    /** 一次离线业务操作。 */
    public interface RequestWork {
        /**
         * 执行业务写入或消息生成。
         * @throws IOException 操作失败
         */
        void run() throws IOException;
    }

    /** 在计时线程生成消息，仅提交后允许 Unity 提取。 */
    private synchronized void tick() {
        if (connection == null || timerFailure != null || !pending.isEmpty()) return;
        long now = System.nanoTime();
        boolean periodic = reply.periodic != null && now >= reply.nextTick;
        if (!periodic && !games.needsPump(connection)) return;
        try {
            committed(() -> {
                if (periodic) {
                    reply.nextTick = now + 1_000_000_000L;
                    reply.periodic.run();
                }
                if (games.needsPump(connection)) games.pump(connection, reply);
            });
        } catch (IOException | RuntimeException failure) {
            timerFailure = new IOException("离线计时操作失败", failure);
        }
    }

    /**
     * 将消息缓冲与数据库提交绑定，回滚时丢弃本次消息。
     * @param action 本次操作
     * @throws IOException 执行或提交失败
     */
    private void committed(RequestWork action) throws IOException {
        List<byte[]> frames = new ArrayList<>();
        reply.buffer = frames;
        try {
            transaction.run(action);
            pending.addAll(frames);
        } finally {
            reply.buffer = null;
        }
    }

    /**
     * 按顺序取走已提交消息。
     * @return 待处理消息
     */
    private List<byte[]> drain() {
        if (pending.isEmpty()) return Collections.emptyList();
        List<byte[]> result = new ArrayList<>(pending);
        pending.clear();
        return result;
    }

    /**
     * 校验 Unity 已开启本地会话。
     * @throws IOException 会话未开启
     */
    private void requireConnected() throws IOException {
        if (connection == null) throw new IOException("离线会话未开启");
    }

    /** 保持业务响应及推送顺序的内存出口。 */
    private final class DirectReply implements GameReply {
        private List<byte[]> buffer;
        private RequestWork periodic;
        private long nextTick;
        @Override public int actorSession() { return connection.id; }
        @Override public void periodic(RequestWork work) { periodic = work; nextTick = System.nanoTime() + 1_000_000_000L; }
        @Override public void send(SprotoProtocol.Request request, Map<String,Object> body) throws IOException {
            if (request.type == 3 || request.type == 3006) {
                android.util.Log.i("PokemonOffline", "生成本地响应 C" + request.type + " errno=" + body.get("errno"));
            }
            write(protocol.response(request, body));
        }
        @Override public void push(int type, Map<String,Object> body) throws IOException { write(protocol.push(type, body)); }
        private void write(byte[] frame) throws IOException {
            if (buffer == null) throw new IOException("消息必须在本地事务中生成");
            buffer.add(frame);
        }
    }
}
