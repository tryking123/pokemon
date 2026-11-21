package com.pokemon.local.gateway;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 守护小精灵本地回环 TCP 游戏网关
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:49
 */
@Component
public final class LocalGameGateway {

    private static final Logger LOG = LoggerFactory.getLogger(LocalGameGateway.class);
    private static final int MAX_CONNECTIONS = 8;
    private static final int IDLE_TIMEOUT_MS = 120_000;

    private final String bind;
    private final int port;
    private final SprotoProtocol protocol;
    private final GameGatewayService games;
    private final Set<Socket> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, MAX_CONNECTIONS, 30,
            TimeUnit.SECONDS, new SynchronousQueue<>());

    private ServerSocket listener;
    private TransactionBoundary transaction;

    /**
     * 初始化本地 TCP 适配器及其监听配置
     *
     * @param bind TCP 回环监听地址
     * @param port 游戏服务端口
     * @param protocol Sproto 包处理器
     * @param games 本地游戏业务
     */
    LocalGameGateway(@Value("${POKEMON_GAME_BIND:127.0.0.1}") String bind,
                     @Value("${POKEMON_GAME_PORT:9002}") int port,
                     SprotoProtocol protocol, GameGatewayService games) {
        this.bind = bind;
        this.port = port;
        this.protocol = protocol;
        this.games = games;
    }

    /**
     * 启动只监听本机回环的网关
     *
     * @throws IOException 监听失败
     */
    @PostConstruct
    public void start() throws IOException {
        InetAddress address = InetAddress.getByName(bind);
        if (!address.isLoopbackAddress() || port < 1 || port > 65535) {
            throw new IOException("游戏网关只能监听回环地址和有效端口");
        }
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(address, port), MAX_CONNECTIONS);
        } catch (IOException exception) {
            socket.close();
            throw exception;
        }
        listener = socket;
        Thread acceptor = new Thread(this::accept, "pokemon-game-listener");
        acceptor.setDaemon(true);
        acceptor.start();
        LOG.info("游戏网关已监听 {}:{}", address.getHostAddress(), port);
    }

    /**
     * 停止监听并关闭现有连接
     */
    @PreDestroy
    public void stop() {
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException exception) {
                LOG.debug("关闭游戏网关监听失败", exception);
            }
        }
        workers.shutdownNow();
        connections.forEach(this::close);
    }

    /**
     * 接收回环连接并交给有界工作线程处理
     */
    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket peer = listener.accept();
                if (!peer.getInetAddress().isLoopbackAddress()) {
                    close(peer);
                    continue;
                }
                connections.add(peer);
                try {
                    workers.execute(() -> serve(peer));
                } catch (RejectedExecutionException rejected) {
                    close(peer);
                }
            } catch (IOException exception) {
                if (!listener.isClosed()) {
                    LOG.warn("游戏网关停止接收连接", exception);
                    stop();
                }
            }
        }
    }

    /**
     * 读取并处理一条客户端连接上的有序消息
     *
     * @param peer 客户端连接
     */
    private void serve(Socket peer) {
        try (Socket socket = peer;
             DataInputStream input = new DataInputStream(socket.getInputStream());
             DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            socket.setSoTimeout(IDLE_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            GameGatewayService.Connection connection = new GameGatewayService.Connection();
            WireReply reply = new WireReply(output, connection.id);
            Set<Integer> unknown = new HashSet<>();
            while (!socket.isClosed()) {
                if (reply.periodic != null && System.nanoTime() >= reply.nextTick) {
                    reply.nextTick = System.nanoTime() + 1_000_000_000L;
                    sendCommitted(reply, reply.periodic);
                }
                socket.setSoTimeout(reply.periodic == null ? IDLE_TIMEOUT_MS : 250);
                int first;
                try {
                    first = input.readUnsignedByte();
                } catch (SocketTimeoutException timeout) {
                    if (reply.periodic == null) throw timeout;
                    continue;
                }
                socket.setSoTimeout(IDLE_TIMEOUT_MS);
                int length = (first << 8) | input.readUnsignedByte();
                if (length == 0) {
                    throw new IOException("无效的零长度游戏帧");
                }
                byte[] payload = new byte[length];
                input.readFully(payload);
                SprotoProtocol.Request request = protocol.decodeRequest(payload);
                sendCommitted(reply, () -> dispatch(connection, request, reply, unknown));
            }
        } catch (EOFException | SocketTimeoutException expected) {
            LOG.debug("游戏网关客户端连接已结束");
        } catch (IOException exception) {
            LOG.debug("游戏网关客户端连接中断：{}", exception.getMessage());
        } catch (RuntimeException | LinkageError exception) {
            LOG.warn("处理本地游戏请求失败", exception);
        } finally {
            connections.remove(peer);
        }
    }

    /** 在同一连接线程中提交请求或定时玩法操作，再发送帧。
     * @param reply 当前连接
     * @param action 请求或定时推进
     * @throws IOException 编码或写入失败
     */
    private void sendCommitted(WireReply reply,RequestWork action) throws IOException {
        if (transaction == null) {
            action.run();
            return;
        }
        reply.buffer = new ArrayList<>();
        transaction.run(action);
        List<byte[]> frames = reply.buffer;
        reply.buffer = null;
        for (byte[] frame : frames) reply.write(frame);
    }

    /**
     * 使用原 TCP 业务分发并报告未迁移的协议。
     *
     * @param connection 已连接的角色会话
     * @param request 客户端请求
     * @param reply 应答通道
     * @param unknown 当前连接已记录的未实现编号
     * @throws IOException 应答编码失败
     */
    private void dispatch(GameGatewayService.Connection connection, SprotoProtocol.Request request,
                          GameReply reply, Set<Integer> unknown) throws IOException {
        if (games.handle(connection, request, reply)) {
            return;
        }
        if (unknown.size() < 30 && unknown.add(request.type)) {
            LOG.info("当前版本未实现游戏请求 type={}", request.type);
        }
        Map<String, Object> failure = protocol.unsupported(request);
        if (!failure.isEmpty()) {
            reply.send(request, failure);
        }
    }

    /**
     * 启动前设置应用私有数据库事务，提交后再输出本次应答。
     *
     * @param boundary SQLite 与 TCP 共用的事务入口
     */
    public void useTransaction(TransactionBoundary boundary) {
        if (listener != null) {
            throw new IllegalStateException("端口已开始监听");
        }
        this.transaction = boundary;
    }

    /** 本地游戏请求事务适配器。 */
    public interface TransactionBoundary {
        /**
         * 在一次已提交的事务内执行游戏请求。
         *
         * @param work 请求逻辑
         * @throws IOException 输入、输出或执行错误
         */
        void run(RequestWork work) throws IOException;
    }

    /** 可报告编码错误的 TCP 请求逻辑。 */
    public interface RequestWork {
        /**
         * 执行一次游戏请求。
         *
         * @throws IOException 请求编码错误
         */
        void run() throws IOException;
    }

    /**
     * 关闭指定客户端连接
     *
     * @param socket 客户端连接
     */
    private void close(Socket socket) {
        connections.remove(socket);
        try {
            socket.close();
        } catch (IOException exception) {
            LOG.debug("关闭游戏客户端连接失败", exception);
        }
    }

    /**
     * 将游戏业务响应写成两字节大端长度和压缩负载
     *
     * @author tongruiyuan
     * @since 2026-11-18 17:49
     */
    private final class WireReply implements GameReply {
        private final DataOutputStream output;
        private final int actorSession;
        private List<byte[]> buffer;
        private RequestWork periodic;
        private long nextTick;

        /** 注册当前玩法在连接线程上的定时推进。
         * @param work 定时操作，空值表示结束
         */
        @Override
        public void periodic(RequestWork work) {
            periodic = work;
            nextTick = System.nanoTime()+1_000_000_000L;
        }

        /**
         * 初始化客户端连接的协议输出
         *
         * @param output 客户端数据输出流
         * @param actorSession 当前连接的场景实体编号
         */
        private WireReply(DataOutputStream output, int actorSession) {
            this.output = output;
            this.actorSession = actorSession;
        }

        /**
         * 返回本连接的玩家场景实体编号。
         *
         * @return S3 已分配的场景实体编号
         */
        @Override
        public int actorSession() {
            return actorSession;
        }

        /**
         * 写入关联原 session 的原协议响应
         *
         * @param request 原客户端请求
         * @param body 响应字段
         * @throws IOException 连接写入失败
         */
        @Override
        public void send(SprotoProtocol.Request request, Map<String, Object> body) throws IOException {
            write(protocol.response(request, body));
        }

        /**
         * 写入服务端主动消息
         *
         * @param type 服务端消息编号
         * @param body 推送字段
         * @throws IOException 连接写入失败
         */
        @Override
        public void push(int type, Map<String, Object> body) throws IOException {
            write(protocol.push(type, body));
        }

        /**
         * 成帧并刷新到客户端连接
         *
         * @param payload 压缩负载
         * @throws IOException 长度超出协议限制或写入失败
         */
        private void write(byte[] payload) throws IOException {
            if (payload.length == 0 || payload.length > 65535) {
                throw new IOException("游戏帧超出可用长度");
            }
            if (buffer != null) {
                buffer.add(payload);
                return;
            }
            output.writeShort(payload.length);
            output.write(payload);
            output.flush();
        }
    }
}
