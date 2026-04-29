package org.springframework.data.redis.laboratory.l3_00;

import org.springframework.data.redis.laboratory.util.RedisConfigUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * L3-00 终极硬核实验：用 JDK 原生 Socket 手搓 RESP 协议与 Redis 对话
 * <p>
 * ★ 零外部依赖！不引入任何 Spring / Jedis / Lettuce / Netty ★
 * <p>
 * 核心目的：
 *   亲眼证明所谓高大上的 Redis "驱动"，
 *   底层本质就是 Socket + RESP 协议的封装翻译官。
 * <p>
 * 运行前提：本地 Redis 已启动 → docker run -d -p 6379:6379 redis:7
 */
public class L300_RawSocketRespLab {

    // ═══════════════════════════════════════════════
    // 连接配置（按你的实际环境修改）
    // ═══════════════════════════════════════════════
    private static final String REDIS_HOST = RedisConfigUtils.getHost();
    private static final int REDIS_PORT = RedisConfigUtils.getPort();
    private static final String REDIS_PASSWORD = RedisConfigUtils.getPassword();

    /** RESP 协议行分隔符 */
    private static final String CRLF = "\r\n";

    public static void main(String[] args) throws Exception {

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║  L3-00 Raw Socket RESP 实验室                            ║");
        System.out.println("║  目的: 证明 Redis 驱动的本质 = Socket + RESP 编码/解码     ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();

        // ═══════════════════════════════════════════════════════════
        // 第一步：用 JDK 原生 Socket 建立 TCP 连接
        // ═══════════════════════════════════════════════════════════
        // 这一步等价于 Jedis 的 jedis.connect()
        //           或 Lettuce 的 Netty Bootstrap.connect()
        // 本质上就是一个 TCP 三次握手，没有任何魔法
        try (Socket socket = new Socket(REDIS_HOST, REDIS_PORT)) {

            System.out.println("[1] TCP 连接建立成功 → " + REDIS_HOST + ":" + REDIS_PORT);
            System.out.println("    本地端口: " + socket.getLocalPort());
            System.out.println("    底层实现: java.net.Socket → OS TCP Socket → 三次握手完成");
            System.out.println();

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // ═══════════════════════════════════════════════════════════
            // (可选) 如果 Redis 配了密码，先发 AUTH 命令
            // ═══════════════════════════════════════════════════════════
            if (REDIS_PASSWORD != null && !REDIS_PASSWORD.isEmpty()) {
                String authCmd = buildRespCommand("AUTH", REDIS_PASSWORD);
                System.out.println("[AUTH] 发送认证命令...");
                printRespHumanReadable(authCmd);
                out.write(authCmd.getBytes(StandardCharsets.UTF_8));
                out.flush();
                String authReply = readReply(in);
                System.out.println("       Redis 回复: " + authReply);
                System.out.println();
            }

            // ═══════════════════════════════════════════════════════════
            // 第二步：手搓 RESP 协议发送 PING 命令
            // ═══════════════════════════════════════════════════════════
            // RESP 协议规定：客户端发送的命令必须是 Array of Bulk Strings
            //   *1\r\n     → 这是一个数组，长度为 1
            //   $4\r\n     → 第一个元素是 Bulk String，长度 4 字节
            //   PING\r\n   → 元素内容
            //
            // Redis 收到后返回 Simple String: +PONG\r\n
            String pingCmd = buildRespCommand("PING");

            System.out.println("[2] ──── 发送 PING 命令 ────");
            System.out.println("    RESP 报文（人类可读）:");
            printRespHumanReadable(pingCmd);
            System.out.println("    RESP 报文（原始字节 hex dump）:");
            printRespHex(pingCmd);

            // 写入 TCP 输出流 —— 这就是 Jedis 源码里 jedis.ping() 底层干的事
            out.write(pingCmd.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // 从 TCP 输入流读取 Redis 响应
            String pingReply = readReply(in);
            System.out.println("    Redis 原生回复: " + pingReply);
            System.out.println("    ┗━ '+' = RESP Simple String 类型，PONG = 内容");
            System.out.println();

            // ═══════════════════════════════════════════════════════════
            // 第三步：手搓 RESP 协议发送 SET 命令
            // ═══════════════════════════════════════════════════════════
            // SET lab:l3-00:proof "I-spoke-RESP" 的 RESP 编码:
            //   *3\r\n                          → 数组长度 3（SET + key + value）
            //   $3\r\n  SET\r\n               → 命令名
            //   $15\r\n lab:l3-00:proof\r\n    → key（15 字节）
            //   $12\r\n I-spoke-RESP\r\n       → value（12 字节）
            String setCmd = buildRespCommand("SET", "lab:l3-00:proof", "I-spoke-RESP");

            System.out.println("[3] ──── 发送 SET lab:l3-00:proof \"I-spoke-RESP\" ────");
            System.out.println("    RESP 报文（人类可读）:");
            printRespHumanReadable(setCmd);

            out.write(setCmd.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String setReply = readReply(in);
            System.out.println("    Redis 原生回复: " + setReply);
            System.out.println("    ┗━ '+OK' = SET 操作成功");
            System.out.println();

            // ═══════════════════════════════════════════════════════════
            // 第四步：手搓 RESP 协议发送 GET 命令
            // ═══════════════════════════════════════════════════════════
            // GET lab:l3-00:proof 的 RESP 编码:
            //   *2\r\n                          → 数组长度 2（GET + key）
            //   $3\r\n  GET\r\n               → 命令名
            //   $15\r\n lab:l3-00:proof\r\n    → key
            //
            // Redis 返回 Bulk String: $12\r\nI-spoke-RESP\r\n
            String getCmd = buildRespCommand("GET", "lab:l3-00:proof");

            System.out.println("[4] ──── 发送 GET lab:l3-00:proof ────");
            System.out.println("    RESP 报文（人类可读）:");
            printRespHumanReadable(getCmd);

            out.write(getCmd.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String getReply = readReply(in);
            System.out.println("    Redis 原生回复: " + getReply);
            System.out.println("    ┗━ '$12' = Bulk String 类型，12 = 内容字节数");
            System.out.println();

            // ═══════════════════════════════════════════════════════════
            // 第五步：发送 DEL 清理实验数据
            // ═══════════════════════════════════════════════════════════
            String delCmd = buildRespCommand("DEL", "lab:l3-00:proof");
            out.write(delCmd.getBytes(StandardCharsets.UTF_8));
            out.flush();
            String delReply = readReply(in);
            System.out.println("[5] DEL lab:l3-00:proof → " + delReply);

            // ═══════════════════════════════════════════════════════════
            // 第六步：发送 QUIT 优雅断开 TCP 连接
            // ═══════════════════════════════════════════════════════════
            String quitCmd = buildRespCommand("QUIT");
            out.write(quitCmd.getBytes(StandardCharsets.UTF_8));
            out.flush();
            String quitReply = readReply(in);
            System.out.println("[6] QUIT → " + quitReply);

        } // try-with-resources: Socket.close() → TCP 四次挥手

        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════");
        System.out.println("  实验结论:");
        System.out.println("  你刚才只用了 JDK 原生 Socket + 手搓字符串，就完成了：");
        System.out.println("    ① TCP 三次握手建立连接");
        System.out.println("    ② 按 RESP 协议编码命令并发送");
        System.out.println("    ③ 接收并解析 Redis 的 RESP 响应");
        System.out.println("    ④ 优雅断开连接");
        System.out.println();
        System.out.println("  所谓 Redis 驱动（Jedis/Lettuce）= Socket + RESP 编解码");
        System.out.println("                                  + 连接池管理");
        System.out.println("                                  + 异常处理");
        System.out.println("                                  + 集群路由");
        System.out.println("  没有魔法，只有协议。");
        System.out.println("══════════════════════════════════════════════════════════");
    }

    // ═══════════════════════════════════════════════════════════════
    //  RESP 协议编码器 —— 手搓版
    // ═══════════════════════════════════════════════════════════════

    /**
     * 将命令参数编码为 RESP Array of Bulk Strings。
     * <p>
     * 这就是 Jedis 的 Protocol.sendCommand() 和
     * Lettuce 的 CommandEncoder.encode() 干的核心活。
     * <p>
     * 例: buildRespCommand("SET", "k", "v")
     *   → "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n"
     */
    private static String buildRespCommand(String... args) {
        StringBuilder sb = new StringBuilder();
        // *N\r\n → 数组头：告诉 Redis "接下来有 N 个元素"
        sb.append('*').append(args.length).append(CRLF);
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            // $len\r\n → Bulk String 头：告诉 Redis "接下来的内容有 len 字节"
            sb.append('$').append(bytes.length).append(CRLF);
            // content\r\n → 实际内容
            sb.append(arg).append(CRLF);
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  RESP 协议解码器 —— 手搓简化版（仅处理单条回复）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从输入流读取一条 RESP 回复。
     * <p>
     * RESP 回复类型由第一个字节决定：
     *   '+' → Simple String（如 +OK\r\n, +PONG\r\n）
     *   '-' → Error（如 -ERR unknown command\r\n）
     *   ':' → Integer（如 :1\r\n）
     *   '$' → Bulk String（如 $6\r\nfoobar\r\n, $-1\r\n 表示 nil）
     *   '*' → Array（如 *2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n）
     */
    private static String readReply(InputStream in) throws Exception {
        int firstByte = in.read();
        if (firstByte == -1) {
            return "(连接已关闭)";
        }
        char type = (char) firstByte;

        switch (type) {
            case '+': // Simple String: +PONG\r\n
            case '-': // Error: -ERR ...\r\n
            case ':': // Integer: :1\r\n
                return type + readLine(in);

            case '$': // Bulk String: $6\r\nfoobar\r\n
                String lenStr = readLine(in);
                int len = Integer.parseInt(lenStr);
                if (len == -1) {
                    return "$-1 (nil)";
                }
                byte[] data = new byte[len];
                int totalRead = 0;
                while (totalRead < len) {
                    int n = in.read(data, totalRead, len - totalRead);
                    if (n == -1) break;
                    totalRead += n;
                }
                in.read(); // 吃掉 \r
                in.read(); // 吃掉 \n
                return "$" + len + " → " + new String(data, StandardCharsets.UTF_8);

            case '*': // Array
                String countStr = readLine(in);
                return "*" + countStr + " (Array, 需递归解析)";

            default:
                return "(未知 RESP 类型: " + type + ")";
        }
    }

    /** 读取到 \r\n 为止的一行（不含 \r\n） */
    private static String readLine(InputStream in) throws Exception {
        StringBuilder line = new StringBuilder();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b == -1) break;
            if (prev == '\r' && b == '\n') {
                line.deleteCharAt(line.length() - 1); // 移除已追加的 \r
                break;
            }
            line.append((char) b);
            prev = b;
        }
        return line.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  调试辅助方法
    // ═══════════════════════════════════════════════════════════════

    /** 以人类可读方式逐行打印 RESP 报文 */
    private static void printRespHumanReadable(String resp) {
        String[] lines = resp.split("\r\n");
        for (String line : lines) {
            if (line.isEmpty()) continue;
            String annotation = annotateRespLine(line);
            System.out.printf("      │ %-20s  ← %s%n", line + "\\r\\n", annotation);
        }
    }

    /** 为 RESP 报文的每一行添加中文注释 */
    private static String annotateRespLine(String line) {
        if (line.startsWith("*")) {
            return "Array 头：接下来有 " + line.substring(1) + " 个元素";
        } else if (line.startsWith("$")) {
            return "Bulk String 头：内容长度 " + line.substring(1) + " 字节";
        } else {
            return "元素内容：\"" + line + "\"";
        }
    }

    /** 以 hex dump 方式打印 RESP 报文原始字节 */
    private static void printRespHex(String resp) {
        byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
        StringBuilder hex = new StringBuilder("      │ ");
        for (byte b : bytes) {
            hex.append(String.format("%02X ", b));
        }
        System.out.println(hex.toString().trim());

        StringBuilder ascii = new StringBuilder("      │ ");
        for (byte b : bytes) {
            if (b == '\r') ascii.append("\\r ");
            else if (b == '\n') ascii.append("\\n ");
            else ascii.append((char) b).append("  ");
        }
        System.out.println(ascii.toString().trim());
    }
}
